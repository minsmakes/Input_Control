#include <windows.h>
#include <windowsx.h>
#include <shellapi.h>
#include <commctrl.h>
#include <dwmapi.h>
#include <uxtheme.h>
#include <string>
#include <vector>
#include <sstream>
#include <algorithm>
#include <map>

extern "C" void ic_status(const void *, wchar_t *, size_t);
extern "C" bool ic_action(const void *, const char *);
namespace {
using Str = std::wstring;
constexpr auto app = L"Input Control";
constexpr auto prefs = L"Software\\Input Control\\Companion";
constexpr auto startup = L"Software\\Microsoft\\Windows\\CurrentVersion\\Run";
enum Id {
  Settings = 100,
  Disconnect,
  Approve,
  Reject,
  Peers,
  Forget,
  SignIn,
  TrayClose,
  Minimized,
  Notices,
  AutoKeys,
  Theme,
  Open,
  Quit,
  StatusText
};
constexpr UINT TRAY = WM_APP + 12;
DWORD read(const wchar_t *path, const wchar_t *name, DWORD fallback) {
  DWORD v = 0, size = sizeof(v);
  return RegGetValueW(HKEY_CURRENT_USER, path, name, RRF_RT_REG_DWORD, nullptr,
                      &v, &size) == ERROR_SUCCESS
             ? v
             : fallback;
}
bool save(const wchar_t *name, DWORD v) {
  return RegSetKeyValueW(HKEY_CURRENT_USER, prefs, name, REG_DWORD, &v,
                         sizeof(v)) == ERROR_SUCCESS;
}
bool signIn() {
  DWORD size = 0;
  return RegGetValueW(HKEY_CURRENT_USER, startup, app, RRF_RT_REG_SZ, nullptr,
                      nullptr, &size) == ERROR_SUCCESS;
}
bool setSignIn(bool on) {
  if (!on)
    return RegDeleteKeyValueW(HKEY_CURRENT_USER, startup, app) == ERROR_SUCCESS;
  wchar_t path[32768];
  DWORD n = GetModuleFileNameW(nullptr, path, 32768);
  if (!n || n >= 32768)
    return false;
  Str quoted = L"\"" + Str(path, n) + L"\"";
  return RegSetKeyValueW(
             HKEY_CURRENT_USER, startup, app, REG_SZ, quoted.c_str(),
             DWORD((quoted.size() + 1) * sizeof(wchar_t))) == ERROR_SUCCESS;
}
Str peerName(const Str &key) {
  wchar_t v[96]{};
  DWORD size = sizeof(v);
  if (RegGetValueW(HKEY_CURRENT_USER, prefs, key.c_str(), RRF_RT_REG_SZ,
                   nullptr, v, &size) == ERROR_SUCCESS)
    return v;
  return L"Android tablet";
}
Str groupCode(const Str &code) {
  Str s;
  for (size_t i = 0; i < code.size(); i += 8) {
    if (i)
      s += L"   ";
    s += code.substr(i, 8);
  }
  return s;
}
struct Window {
  const void *ctx;
  HWND w{};
  HICON icon{};
  HFONT normal{}, title{}, captionFont{};
  HBRUSH brush{};
  std::map<int, HWND> controls;
  std::vector<Str> peers;
  Str listening, pc, key, label, code, cache;
  bool settings = false, dark = false, tray = false;
  DWORD hide = read(prefs, L"TrayClose", 1),
        minimize = read(prefs, L"Minimized", 0),
        notify = read(prefs, L"Notices", 1),
        automatic = read(prefs, L"AutoKeyboard", 1),
        theme = read(prefs, L"Theme", 0);
  UINT taskbar = RegisterWindowMessageW(L"TaskbarCreated");
  COLORREF bg{}, fg{}, muted{}, card{}, accent{};
  explicit Window(const void *c) : ctx(c) {}
  int px(int x) { return MulDiv(x, int(GetDpiForWindow(w)), 96); }
  void fonts() {
    for (HFONT *f : {&normal, &title, &captionFont}) {
      if (*f)
        DeleteObject(*f);
    }
    auto make = [&](int s, int weight) {
      return CreateFontW(-px(s), 0, 0, 0, weight, FALSE, FALSE, FALSE,
                         DEFAULT_CHARSET, OUT_DEFAULT_PRECIS,
                         CLIP_DEFAULT_PRECIS, CLEARTYPE_QUALITY, DEFAULT_PITCH,
                         L"Segoe UI");
    };
    normal = make(15, FW_NORMAL);
    captionFont = make(13, FW_NORMAL);
    title = make(25, FW_SEMIBOLD);
    for (auto [id, h] : controls)
      SendMessageW(
          h, WM_SETFONT,
          (WPARAM)(id == 201 ? title
                             : (id == 202 || id == 205 ? captionFont : normal)),
          TRUE);
  }
  void palette() {
    dark =
        theme == 2 || (theme == 0 && !read(L"Software\\Microsoft\\Windows\\Curr"
                                           L"entVersion\\Themes\\Personalize",
                                           L"AppsUseLightTheme", 1));
    bg = dark ? RGB(25, 28, 35) : RGB(246, 248, 252);
    fg = dark ? RGB(239, 243, 250) : RGB(25, 36, 53);
    muted = dark ? RGB(173, 187, 207) : RGB(76, 93, 115);
    card = dark ? RGB(47, 54, 65) : RGB(255, 255, 255);
    accent = dark ? RGB(21, 89, 199) : RGB(18, 83, 193);
    if (brush)
      DeleteObject(brush);
    brush = CreateSolidBrush(bg);
    BOOL d = dark;
    DwmSetWindowAttribute(w, 20, &d, sizeof(d));
    InvalidateRect(w, nullptr, TRUE);
  }
  HWND add(int id, const wchar_t *cls, const wchar_t *caption, DWORD flags) {
    auto h =
        CreateWindowExW(0, cls, caption, WS_CHILD | flags, 0, 0, 1, 1, w,
                        (HMENU)(INT_PTR)id, GetModuleHandleW(nullptr), nullptr);
    controls[id] = h;
    return h;
  }
  void text(int id, const Str &s) { SetWindowTextW(controls[id], s.c_str()); }
  void put(int id, int x, int y, int width, int height, bool visible = true) {
    auto h = controls[id];
    MoveWindow(h, px(x), px(y), px(width), px(height), TRUE);
    ShowWindow(h, visible ? SW_SHOW : SW_HIDE);
  }
  void layout() {
    RECT r;
    GetClientRect(w, &r);
    int width = MulDiv(r.right, 96, int(GetDpiForWindow(w))),
        inside = width - 48;
    for (auto [id, h] : controls)
      ShowWindow(h, SW_HIDE);
    put(200, 24, 23, 46, 46);
    put(201, 84, 18, width - 220, 36);
    put(202, 85, 56, width - 200, 24);
    put(Settings, width - 127, 26, 103, 38);
    text(Settings, settings ? L"Back" : L"Settings");
    if (settings) {
      const wchar_t *labels[] = {L"Start at Windows sign-in",
                                 L"Close to notification area",
                                 L"Launch minimized",
                                 L"Pairing notifications",
                                 L"Automatic keyboard activation",
                                 L"Appearance"};
      int ids[] = {SignIn, TrayClose, Minimized, Notices, AutoKeys, Theme};
      bool vals[] = {signIn(),     bool(hide),      bool(minimize),
                     bool(notify), bool(automatic), false};
      for (int i = 0; i < 6; i++) {
        text(211 + i, labels[i]);
        put(211 + i, 26, 111 + 53 * i, inside - 120, 29);
        text(ids[i], i == 5 ? (theme == 0   ? L"System"
                               : theme == 1 ? L"Light"
                                            : L"Dark")
                            : (vals[i] ? L"On" : L"Off"));
        put(ids[i], width - 124, 101 + 53 * i, 100, 41);
      }
    } else {
      text(203, pc);
      put(203, 24, 108, inside, 28);
      text(204, listening);
      put(204, 24, 139, inside, 25);
      text(205, code.empty() ? L"DEVICE CONNECTION" : L"PAIRING REQUEST");
      put(205, 24, 194, inside, 22);
      if (!code.empty()) {
        text(206, L"Compare this code on your tablet");
        put(206, 24, 223, inside, 28);
        text(207, groupCode(code));
        put(207, 24, 258, inside, 26);
        put(Approve, 24, 294, 120, 38);
        put(Reject, 152, 294, 100, 38);
      } else {
        text(206, key.empty() ? L"Waiting for a paired tablet" : label);
        put(206, 24, 225, inside, 30);
        text(207, key.empty()
                      ? L"On the tablet, open Devices → Pair New Device."
                      : L"Paired   ·   Secure Wi-Fi connection");
        put(207, 24, 258, inside, 28);
        put(Disconnect, 24, 294, 124, 38, !key.empty());
      }
      text(208, L"Trusted devices");
      put(208, 24, 351, inside, 26);
      if (peers.empty()) {
        text(209, L"No paired devices yet");
        put(209, 24, 389, inside, 26);
      } else {
        put(Peers, 24, 386, inside - 110, 190);
        put(Forget, width - 125, 380, 101, 39);
      }
    }
    InvalidateRect(w, nullptr, TRUE);
  }
  bool trayOp(DWORD op, UINT flags, const Str &head = L"",
              const Str &msg = L"") {
    NOTIFYICONDATAW n{};
    n.cbSize = sizeof(n);
    n.hWnd = w;
    n.uID = 1;
    n.uFlags = flags;
    n.hIcon = icon;
    n.uCallbackMessage = TRAY;
    wcsncpy_s(n.szTip, app, _TRUNCATE);
    wcsncpy_s(n.szInfoTitle, head.c_str(), _TRUNCATE);
    wcsncpy_s(n.szInfo, msg.c_str(), _TRUNCATE);
    n.dwInfoFlags = NIIF_INFO;
    return Shell_NotifyIconW(op, &n) != FALSE;
  }
  void refresh() {
    wchar_t raw[8192]{};
    ic_status(ctx, raw, 8192);
    if (cache == raw)
      return;
    cache = raw;
    Str oldKey = key, oldCode = code;
    std::wistringstream in(cache);
    std::getline(in, listening);
    std::getline(in, pc);
    std::getline(in, key);
    std::getline(in, label);
    std::getline(in, code);
    if (!key.empty() && label != L"Android tablet" && peerName(key) != label)
      RegSetKeyValueW(HKEY_CURRENT_USER, prefs, key.c_str(), REG_SZ,
                      label.c_str(), DWORD((label.size() + 1) * 2));
    if (code != oldCode && !code.empty() && notify)
      trayOp(NIM_MODIFY, NIF_INFO, L"Pairing requested",
             L"Open Input Control to compare the verification code.");
    if (key != oldKey)
      trayOp(NIM_MODIFY, NIF_INFO,
             key.empty() ? L"Tablet disconnected" : L"Tablet connected",
             key.empty() ? pc : label);
    Str selected;
    auto box = controls[Peers];
    int index = (int)SendMessageW(box, CB_GETCURSEL, 0, 0);
    if (index >= 0 && size_t(index) < peers.size())
      selected = peers[index];
    peers.clear();
    SendMessageW(box, CB_RESETCONTENT, 0, 0);
    Str k;
    int choice = 0;
    while (std::getline(in, k))
      if (k.size() == 64) {
        if (k == selected)
          choice = (int)peers.size();
        peers.push_back(k);
        Str display = peerName(k) + L"  ·  " + k.substr(0, 8);
        SendMessageW(box, CB_ADDSTRING, 0, (LPARAM)display.c_str());
      }
    SendMessageW(box, CB_SETCURSEL, choice, 0);
    layout();
  }
  void show(bool prefsPage) {
    settings = prefsPage;
    layout();
    ShowWindow(w, SW_RESTORE);
    SetForegroundWindow(w);
  }
  void change(int id) {
    if (id == Open) {
      show(false);
      return;
    }
    if (id == Settings) {
      show(!settings);
      return;
    }
    if (id == Quit) {
      DestroyWindow(w);
      return;
    }
    std::string action;
    if (id == Approve || id == Reject) {
      if (code.size() != 32)
        return;
      auto tag = code.substr(0, 8);
      action = (id == Approve ? "approve " : "reject ") +
               std::string(tag.begin(), tag.end());
    } else if (id == Disconnect)
      action = "disconnect";
    else if (id == Forget) {
      int i = (int)SendMessageW(controls[Peers], CB_GETCURSEL, 0, 0);
      if (i < 0 || size_t(i) >= peers.size())
        return;
      auto k = peers[i];
      if (MessageBoxW(w, L"Remove this tablet’s pairing?", app,
                      MB_YESNO | MB_ICONQUESTION) != IDYES)
        return;
      action = "revoke " + std::string(k.begin(), k.end());
    } else if (id == SignIn) {
      if (!setSignIn(!signIn()))
        MessageBoxW(w, L"Could not update Windows sign-in settings.", app,
                    MB_ICONERROR);
    } else {
      DWORD *value = nullptr;
      const wchar_t *name = nullptr;
      switch (id) {
      case TrayClose:
        value = &hide;
        name = L"TrayClose";
        break;
      case Minimized:
        value = &minimize;
        name = L"Minimized";
        break;
      case Notices:
        value = &notify;
        name = L"Notices";
        break;
      case AutoKeys:
        value = &automatic;
        name = L"AutoKeyboard";
        break;
      case Theme:
        value = &theme;
        name = L"Theme";
        break;
      default:
        return;
      }
      DWORD next = id == Theme ? (*value + 1) % 3 : !*value;
      if (!save(name, next)) {
        MessageBoxW(w, L"Could not save the setting.", app, MB_ICONERROR);
        return;
      }
      *value = next;
      if (id == Theme)
        palette();
      if (id == AutoKeys)
        action = automatic ? "focus on" : "focus off";
    }
    if (!action.empty() && !ic_action(ctx, action.c_str()))
      MessageBoxW(w, L"The operation could not be completed.", app,
                  MB_ICONWARNING);
    refresh();
    layout();
  }
  void menu() {
    HMENU m = CreatePopupMenu();
    AppendMenuW(m, MF_STRING, Open, L"Open Input Control");
    AppendMenuW(m, MF_GRAYED, 0, listening.c_str());
    Str device =
        key.empty() ? L"No connected tablet" : label + L" · Secure Wi-Fi";
    AppendMenuW(m, MF_GRAYED, 0, device.c_str());
    AppendMenuW(m, MF_SEPARATOR, 0, nullptr);
    AppendMenuW(m, MF_STRING, Settings, L"Settings");
    AppendMenuW(m, MF_STRING, Quit, L"Quit");
    POINT p;
    GetCursorPos(&p);
    SetForegroundWindow(w);
    int cmd = TrackPopupMenu(m, TPM_RETURNCMD | TPM_RIGHTBUTTON, p.x, p.y, 0, w,
                             nullptr);
    DestroyMenu(m);
    if (cmd == Settings)
      show(true);
    else if (cmd)
      change(cmd);
    PostMessageW(w, WM_NULL, 0, 0);
  }
  LRESULT message(UINT msg, WPARAM wp, LPARAM lp) {
    if (msg == taskbar && taskbar) {
      tray = trayOp(NIM_ADD, NIF_ICON | NIF_TIP | NIF_MESSAGE);
      return 0;
    }
    switch (msg) {
    case WM_CREATE: {
      icon = (HICON)LoadImageW(GetModuleHandleW(nullptr), MAKEINTRESOURCEW(1),
                               IMAGE_ICON, 0, 0, LR_DEFAULTSIZE | LR_SHARED);
      SendMessageW(w, WM_SETICON, ICON_BIG, (LPARAM)icon);
      SendMessageW(w, WM_SETICON, ICON_SMALL, (LPARAM)icon);
      for (int i = 200; i <= 216; i++)
        add(i, L"STATIC", L"",
            i == 200 ? SS_ICON | SS_CENTERIMAGE : SS_LEFT | SS_NOPREFIX);
      auto header =
          (HICON)LoadImageW(GetModuleHandleW(nullptr), MAKEINTRESOURCEW(1),
                            IMAGE_ICON, px(46), px(46), LR_SHARED);
      SendMessageW(controls[200], STM_SETICON, (WPARAM)header, 0);
      text(201, app);
      text(202, L"Windows Companion");
      for (int i : {Settings, Disconnect, Approve, Reject, Forget, SignIn,
                    TrayClose, Minimized, Notices, AutoKeys, Theme})
        add(i, L"BUTTON", L"", BS_OWNERDRAW | WS_TABSTOP);
      for (auto item : std::map<int, Str>{{Disconnect, L"Disconnect"},
                                          {Approve, L"Approve"},
                                          {Reject, L"Reject"},
                                          {Forget, L"Remove"}})
        text(item.first, item.second);
      add(Peers, L"COMBOBOX", L"", CBS_DROPDOWNLIST | WS_VSCROLL | WS_TABSTOP);
      fonts();
      palette();
      tray = trayOp(NIM_ADD, NIF_ICON | NIF_TIP | NIF_MESSAGE);
      ic_action(ctx, automatic ? "focus on" : "focus off");
      refresh();
      SetTimer(w, 1, 500, nullptr);
      return 0;
    }
    case WM_TIMER:
      refresh();
      return 0;
    case WM_SIZE:
      if (!controls.empty())
        layout();
      return 0;
    case WM_GETMINMAXINFO: {
      auto m = (MINMAXINFO *)lp;
      m->ptMinTrackSize = {px(470), px(510)};
      return 0;
    }
    case WM_DPICHANGED: {
      auto r = (RECT *)lp;
      SetWindowPos(w, nullptr, r->left, r->top, r->right - r->left,
                   r->bottom - r->top, SWP_NOZORDER);
      fonts();
      layout();
      return 0;
    }
    case WM_SETTINGCHANGE:
      palette();
      return 0;
    case WM_ERASEBKGND: {
      RECT r;
      GetClientRect(w, &r);
      FillRect((HDC)wp, &r, brush);
      return 1;
    }
    case WM_CTLCOLORSTATIC:
    case WM_CTLCOLORLISTBOX: {
      HDC dc = (HDC)wp;
      int id = GetDlgCtrlID((HWND)lp);
      SetTextColor(dc, id == 202 || id == 205 || id == 207 ? muted : fg);
      SetBkColor(dc, bg);
      return (LRESULT)brush;
    }
    case WM_DRAWITEM: {
      auto d = (DRAWITEMSTRUCT *)lp;
      bool primary = d->CtlID == Approve;
      COLORREF color = primary ? accent : card;
      auto fill = CreateSolidBrush(color);
      auto pen =
          CreatePen(PS_SOLID, 1, dark ? RGB(65, 75, 90) : RGB(208, 217, 230));
      auto ob = SelectObject(d->hDC, fill), op = SelectObject(d->hDC, pen);
      auto r = d->rcItem;
      RoundRect(d->hDC, r.left, r.top, r.right, r.bottom, px(14), px(14));
      SelectObject(d->hDC, op);
      SelectObject(d->hDC, ob);
      DeleteObject(fill);
      DeleteObject(pen);
      wchar_t caption[90];
      GetWindowTextW(d->hwndItem, caption, 90);
      SetBkMode(d->hDC, TRANSPARENT);
      SetTextColor(d->hDC, primary ? RGB(255, 255, 255) : fg);
      SelectObject(d->hDC, normal);
      if (d->itemState & ODS_SELECTED)
        OffsetRect(&r, 1, 1);
      DrawTextW(d->hDC, caption, -1, &r,
                DT_CENTER | DT_VCENTER | DT_SINGLELINE);
      if (d->itemState & ODS_FOCUS) {
        InflateRect(&r, -4, -4);
        DrawFocusRect(d->hDC, &r);
      }
      return TRUE;
    }
    case WM_COMMAND:
      if (HIWORD(wp) == BN_CLICKED)
        change(LOWORD(wp));
      return 0;
    case TRAY:
      if (lp == WM_LBUTTONUP)
        show(false);
      else if (lp == WM_RBUTTONUP || lp == WM_CONTEXTMENU)
        menu();
      return 0;
    case WM_ENDSESSION:
      if (wp) DestroyWindow(w);
      return 0;
    case WM_CLOSE:
      if (hide && tray)
        ShowWindow(w, SW_HIDE);
      else
        DestroyWindow(w);
      return 0;
    case WM_DESTROY:
      KillTimer(w, 1);
      trayOp(NIM_DELETE, 0);
      ic_action(ctx, "quit");
      PostQuitMessage(0);
      return 0;
    }
    return DefWindowProcW(w, msg, wp, lp);
  }
  ~Window() {
    if (brush)
      DeleteObject(brush);
    for (HFONT f : {normal, title, captionFont})
      if (f)
        DeleteObject(f);
  }
};
LRESULT CALLBACK proc(HWND w, UINT m, WPARAM wp, LPARAM lp) {
  auto self = (Window *)GetWindowLongPtrW(w, GWLP_USERDATA);
  if (m == WM_NCCREATE) {
    self = (Window *)((CREATESTRUCTW *)lp)->lpCreateParams;
    self->w = w;
    SetWindowLongPtrW(w, GWLP_USERDATA, (LONG_PTR)self);
  }
  return self ? self->message(m, wp, lp) : DefWindowProcW(w, m, wp, lp);
}
} // namespace
extern "C" int ic_window(const void *ctx) {
  SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
  Window state(ctx);
  WNDCLASSW wc{};
  wc.hInstance = GetModuleHandleW(nullptr);
  wc.lpfnWndProc = proc;
  wc.lpszClassName = L"InputControlCompanion";
  wc.hCursor = LoadCursorW(nullptr, IDC_ARROW);
  wc.hIcon = LoadIconW(wc.hInstance, MAKEINTRESOURCEW(1));
  RegisterClassW(&wc);
  int dpi = GetDpiForSystem();
  HWND w = CreateWindowExW(
      0, wc.lpszClassName, app, WS_OVERLAPPEDWINDOW | WS_CLIPCHILDREN,
      CW_USEDEFAULT, CW_USEDEFAULT, MulDiv(540, dpi, 96), MulDiv(525, dpi, 96),
      nullptr, nullptr, wc.hInstance, &state);
  if (!w)
    return 0;
  if (!state.minimize || !state.tray)
    ShowWindow(w, SW_SHOW);
  MSG m;
  while (GetMessageW(&m, nullptr, 0, 0) > 0) {
    if (!IsDialogMessageW(w, &m)) {
      TranslateMessage(&m);
      DispatchMessageW(&m);
    }
  }
  return 1;
}
extern "C" void ic_message(const char *text) {
  int n = MultiByteToWideChar(CP_UTF8, 0, text, -1, nullptr, 0);
  std::vector<wchar_t> buf(n);
  MultiByteToWideChar(CP_UTF8, 0, text, -1, buf.data(), n);
  MessageBoxW(nullptr, buf.data(), app, MB_OK | MB_ICONERROR);
}

// A duplicate may arrive before the owner has finished creating its hidden window.
extern "C" void ic_activate_existing(){
  for(int i=0;i<60;++i){
    HWND w=FindWindowW(L"InputControlCompanion",nullptr);
    if(w){
      DWORD pid=0;GetWindowThreadProcessId(w,&pid);AllowSetForegroundWindow(pid);
      PostMessageW(w,WM_COMMAND,Open,0);return;
    }
    Sleep(50);
  }
}
