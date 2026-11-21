#include <windows.h>
#include <shlobj.h>
#include <shellapi.h>
#include <propsys.h>
#include <propkey.h>
#include <wrl.h>
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Foundation.Collections.h>
#include <winrt/Windows.Data.Json.h>
#include <winrt/Windows.Media.Control.h>
#include <winrt/Windows.Graphics.Imaging.h>
#include <winrt/Windows.Security.Cryptography.h>
#include <winrt/Windows.Storage.Streams.h>
#include <algorithm>
#include <map>
#include <mutex>
#include <string>
#include <atomic>
#include <thread>
#include <condition_variable>

using namespace winrt;
using namespace winrt::Windows::Data::Json;
using namespace winrt::Windows::Media::Control;
using namespace winrt::Windows::Graphics::Imaging;
using namespace winrt::Windows::Storage::Streams;
using Microsoft::WRL::ComPtr;
using winrt::Windows::Security::Cryptography::CryptographicBuffer;

namespace {
std::wstring itemName(IShellItem *item, SIGDN type) {
  PWSTR raw = nullptr;
  if (FAILED(item->GetDisplayName(type, &raw)))
    return {};
  std::wstring result(raw);
  CoTaskMemFree(raw);
  return result;
}
std::wstring leaf(std::wstring path) {
  path = path.substr(path.find_last_of(L"\\/") + 1);
  std::transform(path.begin(), path.end(), path.begin(), ::towlower);
  return path;
}
std::wstring foreground() {
  DWORD pid = 0;
  GetWindowThreadProcessId(GetForegroundWindow(), &pid);
  HANDLE p = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, pid);
  if (!p)
    return {};
  wchar_t path[32768];
  DWORD length = 32768;
  bool ok = QueryFullProcessImageNameW(p, 0, path, &length);
  CloseHandle(p);
  return ok ? leaf(std::wstring(path, length)) : L"";
}
hstring encodeImage(InMemoryRandomAccessStream const &stream) {
  if (stream.Size() > 16384)
    return {};
  stream.Seek(0);
  DataReader reader(stream);
  auto n = reader.LoadAsync(static_cast<uint32_t>(stream.Size())).get();
  return CryptographicBuffer::EncodeToBase64String(reader.ReadBuffer(n));
}
hstring appIcon(IShellItem *item) {
  ComPtr<IShellItemImageFactory> factory;
  HBITMAP bitmap{};
  if (FAILED(item->QueryInterface(IID_PPV_ARGS(&factory))) ||
      FAILED(factory->GetImage({32, 32}, SIIGBF_ICONONLY, &bitmap)))
    return {};
  BITMAP b{};
  GetObject(bitmap, sizeof(b), &b);
  if (b.bmWidth < 1 || b.bmWidth > 128 || b.bmHeight < 1 || b.bmHeight > 128) {
    DeleteObject(bitmap);
    return {};
  }
  BITMAPINFO info{};
  info.bmiHeader = {
      sizeof(BITMAPINFOHEADER), b.bmWidth, -b.bmHeight, 1, 32, BI_RGB};
  std::vector<uint8_t> pixels(b.bmWidth * b.bmHeight * 4);
  HDC dc = GetDC(nullptr);
  int rows = GetDIBits(dc, bitmap, 0, b.bmHeight, pixels.data(), &info,
                       DIB_RGB_COLORS);
  ReleaseDC(nullptr, dc);
  DeleteObject(bitmap);
  if (!rows)
    return {};
  InMemoryRandomAccessStream stream;
  auto enc =
      BitmapEncoder::CreateAsync(BitmapEncoder::PngEncoderId(), stream).get();
  enc.SetPixelData(BitmapPixelFormat::Bgra8, BitmapAlphaMode::Premultiplied,
                   b.bmWidth, b.bmHeight, 96, 96, pixels);
  enc.FlushAsync().get();
  return encodeImage(stream);
}
struct Dashboard {
  std::mutex mutex;
  std::condition_variable wake;
  bool stop = false;
  int launch = 0;
  std::string snapshots[2];
  std::map<int, ComPtr<IShellItem>> items;
  std::thread worker;
  Dashboard() : worker([this] { run(); }) {}
  ~Dashboard() {
    {
      std::lock_guard lock(mutex);
      stop = true;
    }
    wake.notify_one();
    worker.join();
  }
  void publish(int kind, hstring value) {
    auto bytes = to_string(value);
    if (bytes.size() > 1024 * 1024)
      return;
    std::lock_guard lock(mutex);
    snapshots[kind] = std::move(bytes);
  }
  void catalog() {
    ComPtr<IShellItem> folder;
    ComPtr<IEnumShellItems> enumerator;
    if (FAILED(SHGetKnownFolderItem(FOLDERID_AppsFolder, KF_FLAG_DEFAULT,
                                    nullptr, IID_PPV_ARGS(&folder))) ||
        FAILED(folder->BindToHandler(nullptr, BHID_EnumItems,
                                     IID_PPV_ARGS(&enumerator))))
      return;
    JsonArray apps;
    std::map<int, ComPtr<IShellItem>> found;
    ComPtr<IShellItem> item;
    while (apps.Size() < 256 &&
           enumerator->Next(1, item.ReleaseAndGetAddressOf(), nullptr) ==
               S_OK) {
      {
        std::lock_guard lock(mutex);
        if (stop)
          return;
      }
      auto key = itemName(item.Get(), SIGDN_PARENTRELATIVEPARSING);
      auto name = itemName(item.Get(), SIGDN_NORMALDISPLAY);
      if (key.empty() || name.empty())
        continue;
      uint32_t hash = 2166136261u;
      for (auto c : key)
        hash = (hash ^ c) * 16777619u;
      int id = static_cast<int>(hash & 0x7fffffff);
      if (!id || found.count(id))
        continue;
      JsonObject app;
      app.Insert(L"id", JsonValue::CreateNumberValue(id));
      app.Insert(L"name", JsonValue::CreateStringValue(name.substr(0, 96)));
      ComPtr<IShellItem2> details;
      PWSTR target = nullptr;
      if (SUCCEEDED(item.As(&details)) &&
          SUCCEEDED(details->GetString(PKEY_Link_TargetParsingPath, &target)) &&
          target) {
        app.Insert(L"exe", JsonValue::CreateStringValue(leaf(target)));
        CoTaskMemFree(target);
      }
      try {
        app.Insert(L"icon", JsonValue::CreateStringValue(appIcon(item.Get())));
      } catch (hresult_error const &) {
      }
      apps.Append(app);
      found.emplace(id, item);
    }
    items.swap(found);
    publish(0, apps.Stringify());
  }
  void activate(int id) {
    auto it = items.find(id);
    if (it == items.end())
      return;
    PIDLIST_ABSOLUTE pidl{};
    if (FAILED(SHGetIDListFromObject(it->second.Get(), &pidl)))
      return;
    SHELLEXECUTEINFOW launchInfo{sizeof(launchInfo)};
    launchInfo.fMask = SEE_MASK_IDLIST | SEE_MASK_FLAG_NO_UI;
    launchInfo.lpIDList = pidl;
    launchInfo.nShow = SW_SHOWNORMAL;
    ShellExecuteExW(&launchInfo);
    CoTaskMemFree(pidl);
  }
  void run() noexcept {
    try {
      init_apartment(apartment_type::multi_threaded);
      GlobalSystemMediaTransportControlsSessionManager manager{nullptr};
      try {
        manager =
            GlobalSystemMediaTransportControlsSessionManager::RequestAsync()
                .get();
      } catch (hresult_error const &) {
      }
      auto refresh = std::chrono::steady_clock::now();
      hstring art, lastKey;
      for (;;) {
        int requested;
        {
          std::lock_guard lock(mutex);
          if (stop)
            break;
          requested = launch;
          launch = 0;
        }
        if (requested)
          activate(requested);
        if (std::chrono::steady_clock::now() >= refresh) {
          try {
            catalog();
          } catch (hresult_error const &) {
          }
          refresh = std::chrono::steady_clock::now() + std::chrono::minutes(5);
        }
        JsonObject state;
        state.Insert(L"foreground", JsonValue::CreateStringValue(foreground()));
        try {
          auto session = manager ? manager.GetCurrentSession() : nullptr;
          if (session) {
            auto info = session.TryGetMediaPropertiesAsync().get();
            auto time = session.GetTimelineProperties();
            auto playback = session.GetPlaybackInfo();
            state.Insert(L"title", JsonValue::CreateStringValue(info.Title()));
            state.Insert(L"artist",
                         JsonValue::CreateStringValue(info.Artist()));
            state.Insert(
                L"playing",
                JsonValue::CreateBooleanValue(
                    playback.PlaybackStatus() ==
                    GlobalSystemMediaTransportControlsSessionPlaybackStatus::
                        Playing));
            auto duration = time.EndTime() - time.StartTime();
            if (duration.count() > 0) {
              auto position = time.Position() - time.StartTime();
              if (playback.PlaybackStatus() ==
                      GlobalSystemMediaTransportControlsSessionPlaybackStatus::
                          Playing &&
                  time.LastUpdatedTime().time_since_epoch().count() > 0) {
                auto rate = playback.PlaybackRate();
                position +=
                    winrt::Windows::Foundation::TimeSpan(static_cast<int64_t>(
                        (winrt::clock::now() - time.LastUpdatedTime()).count() *
                        (rate ? rate.Value() : 1.0)));
              }
              state.Insert(L"duration", JsonValue::CreateNumberValue(
                                            duration.count() / 10000000));
              state.Insert(L"position", JsonValue::CreateNumberValue(
                                            std::clamp(position.count(), 0LL,
                                                       duration.count()) /
                                            10000000));
            }
            auto key = session.SourceAppUserModelId() + L"\n" + info.Title() +
                       L"\n" + info.Artist();
            if (key != lastKey) {
              lastKey = key;
              art = L"";
              if (auto thumb = info.Thumbnail()) {
                auto src = thumb.OpenReadAsync().get();
                auto dec = BitmapDecoder::CreateAsync(src).get();
                BitmapTransform transform;
                transform.ScaledWidth(72);
                transform.ScaledHeight(72);
                auto pixels =
                    dec.GetPixelDataAsync(
                           BitmapPixelFormat::Bgra8, BitmapAlphaMode::Ignore,
                           transform,
                           ExifOrientationMode::RespectExifOrientation,
                           ColorManagementMode::DoNotColorManage)
                        .get()
                        .DetachPixelData();
                InMemoryRandomAccessStream stream;
                auto enc = BitmapEncoder::CreateAsync(
                               BitmapEncoder::JpegEncoderId(), stream)
                               .get();
                enc.SetPixelData(BitmapPixelFormat::Bgra8,
                                 BitmapAlphaMode::Ignore, 72, 72, 96, 96,
                                 pixels);
                enc.FlushAsync().get();
                art = encodeImage(stream);
              }
            }
            state.Insert(L"art", JsonValue::CreateStringValue(art));
          } else {
            lastKey = L"";
            art = L"";
          }
        } catch (hresult_error const &) {
        }
        publish(1, state.Stringify());
        std::unique_lock lock(mutex);
        wake.wait_for(lock, std::chrono::seconds(2),
                      [&] { return stop || launch; });
      }
    } catch (...) {
    }
  }
};
} // namespace
extern "C" void *dashboard_open() {
  try {
    return new Dashboard;
  } catch (...) {
    return nullptr;
  }
}
extern "C" void dashboard_close(Dashboard *d) { delete d; }
extern "C" void dashboard_launch(Dashboard *d, int id) {
  if (d) {
    std::lock_guard lock(d->mutex);
    d->launch = id;
    d->wake.notify_one();
  }
}
extern "C" size_t dashboard_take(Dashboard *d, int kind, char *buf,
                                 size_t capacity) {
  if (!d)
    return 0;
  std::lock_guard lock(d->mutex);
  auto &s = d->snapshots[kind];
  if (s.size() > capacity)
    return s.size();
  auto n = s.size();
  if (n) {
    memcpy(buf, s.data(), n);
    s.clear();
  }
  return n;
}
