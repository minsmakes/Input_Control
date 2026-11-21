#include <windows.h>
#include <mmdeviceapi.h>
#include <endpointvolume.h>
#include <functiondiscoverykeys_devpkey.h>
#include <wrl.h>
#include <atomic>
#include <algorithm>
#include <cmath>
#include <string>
#include <vector>
using namespace Microsoft::WRL;
namespace {
class Changes final
    : public RuntimeClass<RuntimeClassFlags<ClassicCom>, IMMNotificationClient,
                          IAudioEndpointVolumeCallback> {
public:
  std::atomic<unsigned> dirty{3};
  HRESULT STDMETHODCALLTYPE OnNotify(PAUDIO_VOLUME_NOTIFICATION_DATA) override {
    dirty.fetch_or(1);
    return S_OK;
  }
  HRESULT STDMETHODCALLTYPE OnDeviceStateChanged(LPCWSTR, DWORD) override {
    return topology();
  }
  HRESULT STDMETHODCALLTYPE OnDeviceAdded(LPCWSTR) override {
    return topology();
  }
  HRESULT STDMETHODCALLTYPE OnDeviceRemoved(LPCWSTR) override {
    return topology();
  }
  HRESULT STDMETHODCALLTYPE OnPropertyValueChanged(LPCWSTR,
                                                   const PROPERTYKEY) override {
    return topology();
  }
  HRESULT STDMETHODCALLTYPE OnDefaultDeviceChanged(EDataFlow f, ERole r,
                                                   LPCWSTR) override {
    return f == eRender && r == eMultimedia ? topology() : S_OK;
  }
  HRESULT topology() {
    dirty.fetch_or(3);
    return S_OK;
  }
};
struct Output {
  std::wstring name;
  bool current;
};
struct Audio {
  HRESULT apartment = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
  ComPtr<Changes> changes = Make<Changes>();
  ComPtr<IMMDeviceEnumerator> devices;
  ComPtr<IAudioEndpointVolume> endpoint;
  std::vector<Output> outputs;
  Audio() {
    if (SUCCEEDED(apartment) &&
        SUCCEEDED(CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr,
                                   CLSCTX_ALL, IID_PPV_ARGS(&devices))))
      devices->RegisterEndpointNotificationCallback(changes.Get());
  }
  void detach() {
    if (endpoint) {
      endpoint->UnregisterControlChangeNotify(changes.Get());
      endpoint.Reset();
    }
  }
  void bind() {
    detach();
    outputs.clear();
    if (!devices)
      return;
    ComPtr<IMMDevice> current;
    std::wstring currentId;
    if (SUCCEEDED(
            devices->GetDefaultAudioEndpoint(eRender, eMultimedia, &current))) {
      LPWSTR id = nullptr;
      if (SUCCEEDED(current->GetId(&id))) {
        currentId = id;
        CoTaskMemFree(id);
      }
      if (SUCCEEDED(current->Activate(__uuidof(IAudioEndpointVolume),
                                      CLSCTX_ALL, nullptr,
                                      (void **)endpoint.GetAddressOf())))
        endpoint->RegisterControlChangeNotify(changes.Get());
    }
    ComPtr<IMMDeviceCollection> list;
    UINT count = 0;
    if (FAILED(
            devices->EnumAudioEndpoints(eRender, DEVICE_STATE_ACTIVE, &list)) ||
        FAILED(list->GetCount(&count)))
      return;
    for (UINT i = 0; i < count && outputs.size() < 32; i++) {
      ComPtr<IMMDevice> device;
      ComPtr<IPropertyStore> props;
      if (FAILED(list->Item(i, &device)) ||
          FAILED(device->OpenPropertyStore(STGM_READ, &props)))
        continue;
      PROPVARIANT v{};
      LPWSTR id = nullptr;
      if (SUCCEEDED(props->GetValue(PKEY_Device_FriendlyName, &v)) &&
          v.vt == VT_LPWSTR && v.pwszVal && SUCCEEDED(device->GetId(&id))) {
        outputs.push_back({v.pwszVal, currentId == id});
        CoTaskMemFree(id);
      }
      PropVariantClear(&v);
    }
  }
  ~Audio() {
    detach();
    if (devices)
      devices->UnregisterEndpointNotificationCallback(changes.Get());
    devices.Reset();
    changes.Reset();
    if (SUCCEEDED(apartment))
      CoUninitialize();
  }
};
} // namespace
extern "C" void *ic_audio_open() { return new (std::nothrow) Audio; }
extern "C" void ic_audio_close(Audio *a) { delete a; }
extern "C" int ic_audio_state(Audio *a, unsigned char *level,
                              unsigned char *mute) {
  if (!a)
    return -1;
  auto flags = a->changes->dirty.exchange(0);
  if (!flags)
    return -1;
  if (flags & 2)
    a->bind();
  float value = 0;
  BOOL muted = FALSE;
  *level = (a->endpoint &&
            SUCCEEDED(a->endpoint->GetMasterVolumeLevelScalar(&value)) &&
            SUCCEEDED(a->endpoint->GetMute(&muted)))
               ? (unsigned char)std::lround(value * 100)
               : 255;
  *mute = !!muted;
  return (int)a->outputs.size();
}
extern "C" int ic_audio_label(Audio *a, int i, wchar_t *out, int cap,
                              bool *current) {
  if (!a || i < 0 || (size_t)i >= a->outputs.size() || cap < 1)
    return 0;
  auto &v = a->outputs[i];
  int n = (int)(std::min)(v.name.size(), (size_t)cap);
  v.name.copy(out, n);
  *current = v.current;
  return n;
}
extern "C" void ic_audio_command(Audio *a, int cmd) {
  if (cmd >= 2 && cmd <= 4) {
    const WORD keys[] = {VK_MEDIA_PLAY_PAUSE, VK_MEDIA_PREV_TRACK,
                         VK_MEDIA_NEXT_TRACK};
    INPUT pair[2]{};
    pair[0].type = pair[1].type = INPUT_KEYBOARD;
    pair[0].ki.wVk = pair[1].ki.wVk = keys[cmd - 2];
    pair[1].ki.dwFlags = KEYEVENTF_KEYUP;
    SendInput(2, pair, sizeof(INPUT));
    return;
  }
  if (!a)
    return;
  if (a->changes->dirty.load() & 2)
    a->bind();
  if (!a->endpoint)
    return;
  auto ep = a->endpoint.Get();
  if (cmd == 0)
    ep->VolumeStepUp(nullptr);
  else if (cmd == 1)
    ep->VolumeStepDown(nullptr);
  else if (cmd == 5) {
    BOOL m;
    if (SUCCEEDED(ep->GetMute(&m)))
      ep->SetMute(!m, nullptr);
  } else if (cmd == 6 || cmd == 7)
    ep->SetMute(cmd == 7, nullptr);
  else if (cmd >= 100 && cmd <= 200)
    ep->SetMasterVolumeLevelScalar((cmd - 100) / 100.f, nullptr);
  a->changes->dirty.fetch_or(1);
}
