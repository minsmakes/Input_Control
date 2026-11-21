#include <windows.h>
#include <UIAutomation.h>
#include <wrl/client.h>
#include <atomic>
#include <mutex>
#include <thread>
#include <vector>
using Microsoft::WRL::ComPtr;

namespace {
enum Kind : unsigned { Unknown, Editable, Lost };
Kind classify(IUIAutomationElement *e) {
  CONTROLTYPEID type = 0;
  BOOL enabled = FALSE, focused = FALSE;
  if (!e || FAILED(e->get_CurrentControlType(&type)) ||
      FAILED(e->get_CurrentIsEnabled(&enabled)) ||
      FAILED(e->get_CurrentHasKeyboardFocus(&focused)))
    return Unknown;
  if (!enabled || !focused)
    return Lost;
  if (type != UIA_EditControlTypeId && type != UIA_DocumentControlTypeId &&
      type != UIA_ComboBoxControlTypeId)
    return Lost;
  ComPtr<IUIAutomationValuePattern> value;
  if (SUCCEEDED(
          e->GetCurrentPatternAs(UIA_ValuePatternId, IID_PPV_ARGS(&value))) &&
      value) {
    BOOL ro;
    if (SUCCEEDED(value->get_CurrentIsReadOnly(&ro)))
      return ro ? Lost : Editable;
  }
  ComPtr<IUIAutomationTextPattern> text;
  ComPtr<IUIAutomationTextRange> range;
  if (SUCCEEDED(
          e->GetCurrentPatternAs(UIA_TextPatternId, IID_PPV_ARGS(&text))) &&
      text && SUCCEEDED(text->get_DocumentRange(&range)) && range) {
    VARIANT v;
    VariantInit(&v);
    HRESULT hr = range->GetAttributeValue(UIA_IsReadOnlyAttributeId, &v);
    Kind k = SUCCEEDED(hr) && v.vt == VT_BOOL ? (v.boolVal ? Lost : Editable)
                                              : Unknown;
    VariantClear(&v);
    if (k != Unknown)
      return k;
  }
  VARIANT v;
  VariantInit(&v);
  bool editable = SUCCEEDED(e->GetCurrentPropertyValue(
                      UIA_IsTextEditPatternAvailablePropertyId, &v)) &&
                  v.vt == VT_BOOL && v.boolVal;
  VariantClear(&v);
  return editable ? Editable : Unknown;
}
struct Handler final : IUIAutomationFocusChangedEventHandler {
  std::atomic<ULONG> refs{1};
  std::atomic<unsigned> stamp{0};
  HANDLE refresh = CreateEventW(nullptr,FALSE,FALSE,nullptr);
  ~Handler(){ CloseHandle(refresh); }
  std::mutex lock;
  std::vector<int> previous;
  HRESULT STDMETHODCALLTYPE QueryInterface(REFIID iid, void **out) override {
    if (!out)
      return E_POINTER;
    *out = nullptr;
    if (iid != IID_IUnknown &&
        iid != __uuidof(IUIAutomationFocusChangedEventHandler))
      return E_NOINTERFACE;
    *out = this;
    AddRef();
    return S_OK;
  }
  ULONG STDMETHODCALLTYPE AddRef() override { return ++refs; }
  ULONG STDMETHODCALLTYPE Release() override {
    auto n = --refs;
    if (!n)
      delete this;
    return n;
  }
  HRESULT STDMETHODCALLTYPE
  HandleFocusChangedEvent(IUIAutomationElement *) override {
    SetEvent(refresh);
    return S_OK;
  }
  void snapshot(IUIAutomationElement *e) {
    auto k = classify(e);
    std::vector<int> id;
    SAFEARRAY *a = nullptr;
    if (k == Editable && SUCCEEDED(e->GetRuntimeId(&a)) && a) {
      LONG lo, hi;
      int *data = nullptr;
      if (SUCCEEDED(SafeArrayGetLBound(a, 1, &lo)) &&
          SUCCEEDED(SafeArrayGetUBound(a, 1, &hi)) && hi >= lo &&
          hi - lo < 128 && SUCCEEDED(SafeArrayAccessData(a, (void **)&data))) {
        id.assign(data, data + (hi - lo + 1));
        SafeArrayUnaccessData(a);
      }
      SafeArrayDestroy(a);
    }
    std::lock_guard<std::mutex> guard(lock);
    auto old = stamp.load();
    if ((old & 3) != k || (k == Editable && id != previous)) {
      stamp.store(((old & ~3u) + 4) | k);
      previous = std::move(id);
    }
  }
};
struct Watch {
  HANDLE stop = CreateEventW(nullptr, TRUE, FALSE, nullptr);
  Handler *handler = new Handler;
  std::thread worker;
  Watch()
      : worker([this] {
          if (FAILED(CoInitializeEx(nullptr, COINIT_MULTITHREADED)))
            return;
          {
            ComPtr<IUIAutomation> u;
            if (SUCCEEDED(CoCreateInstance(CLSID_CUIAutomation, nullptr,
                                           CLSCTX_INPROC_SERVER,
                                           IID_PPV_ARGS(&u)))) {
              const bool registered=SUCCEEDED(u->AddFocusChangedEventHandler(nullptr, handler));
              HANDLE events[]={stop,handler->refresh};
              do {
                // Allow the provider to update its keyboard-focus and editability properties.
                if(WaitForSingleObject(stop,65)!=WAIT_TIMEOUT) break;
                ComPtr<IUIAutomationElement> e;
                u->GetFocusedElement(&e);
                handler->snapshot(e.Get());
              } while(WaitForMultipleObjects(2,events,FALSE,INFINITE)==WAIT_OBJECT_0+1);
              if(registered) u->RemoveFocusChangedEventHandler(handler);
            }
          }
          CoUninitialize();
        }) {}
  ~Watch() {
    SetEvent(stop);
    worker.join();
    CloseHandle(stop);
    handler->Release();
  }
};
} // namespace
extern "C" void *ic_focus_start() {
  try {
    return new Watch;
  } catch (...) {
    return nullptr;
  }
}
extern "C" void ic_focus_refresh(void *p){if(p) SetEvent(static_cast<Watch*>(p)->handler->refresh);}
extern "C" unsigned ic_focus_read(void *p) {
  return p ? static_cast<Watch *>(p)->handler->stamp.load() : 0;
}
extern "C" void ic_focus_stop(void *p) { delete static_cast<Watch *>(p); }
