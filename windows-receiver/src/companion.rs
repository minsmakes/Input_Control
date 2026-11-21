use super::*;
use std::{
    ffi::{c_char, c_void, CStr, CString},
    ptr,
};
extern "C" {
    fn ic_window(context: *const c_void) -> i32;
    fn ic_message(message: *const c_char);
    fn ic_focus_start() -> *mut c_void;
    fn ic_focus_read(w: *mut c_void) -> u32;
    fn ic_focus_refresh(w: *mut c_void);
    fn ic_focus_stop(w: *mut c_void);
}
#[derive(Default)]
pub struct Status {
    pub listener: String,
    pub connected: Option<([u8; 32], String)>,
    pub automatic: bool,
}
pub struct FocusWatch(*mut c_void);
impl Default for FocusWatch {
    fn default() -> Self {
        Self(ptr::null_mut())
    }
}
impl FocusWatch {
    pub fn after_click(&self) {
        unsafe { ic_focus_refresh(self.0) }
    }
    pub fn sample(&mut self, enabled: bool) -> u32 {
        unsafe {
            if enabled && self.0.is_null() {
                self.0 = ic_focus_start();
            }
            if !enabled && !self.0.is_null() {
                ic_focus_stop(self.0);
                self.0 = ptr::null_mut();
            }
            ic_focus_read(self.0)
        }
    }
}
impl Drop for FocusWatch {
    fn drop(&mut self) {
        unsafe { ic_focus_stop(self.0) }
    }
}
pub fn error(msg: &str) {
    if let Ok(s) = CString::new(msg) {
        unsafe { ic_message(s.as_ptr()) }
    }
}
pub fn run() -> Result<()> {
    let c = Arc::new(Mutex::new(Console {
        store: Store::open()?,
        socket: None,
        stop: false,
        prompt: None,
        revision: 0,
        ui: Status {
            listener: "Starting…".into(),
            ..Default::default()
        },
    }));
    let net = c.clone();
    let worker = thread::spawn(move || {
        lifecycle::log("network worker started");
        if let Err(e) = listen(net.clone()) {
            lifecycle::log(&format!("listener failed: {e}"));
            net.lock().unwrap().ui.listener = format!("Stopped · {e}")
        }
        lifecycle::log("network worker ended");
    });
    let result = unsafe { ic_window(&c as *const Shared as _) };
    command(&c, "quit")?;
    worker
        .join()
        .map_err(|_| err("Receiver worker stopped unexpectedly"))?;
    if result == 0 {
        return Err(err("Cannot create the companion window"));
    }
    Ok(())
}
// GUI calls occur on the message-loop thread while run() owns the Shared handle.
#[no_mangle]
unsafe extern "C" fn ic_status(context: *const Shared, out: *mut u16, capacity: usize) {
    let g = (*context).lock().unwrap();
    let (key, label) =
        g.ui.connected
            .as_ref()
            .map(|(k, l)| (hexkey(k), l.as_str()))
            .unwrap_or_default();
    let mut text = format!(
        "{}\n{}\n{}\n{}\n{}",
        g.ui.listener.replace(['\r', '\n'], " "),
        std::env::var("COMPUTERNAME").unwrap_or_default(),
        key,
        label,
        g.prompt
            .as_ref()
            .map(|p| p.verification.as_str())
            .unwrap_or("")
    );
    for peer in &g.store.peers {
        text.push('\n');
        text.push_str(&hexkey(peer));
    }
    let mut len = 0;
    for c in text.encode_utf16().take(capacity.saturating_sub(1)) {
        out.add(len).write(c);
        len += 1;
    }
    if capacity > 0 {
        out.add(len).write(0);
    }
}
#[no_mangle]
unsafe extern "C" fn ic_action(context: *const Shared, action: *const c_char) -> bool {
    let Ok(action) = CStr::from_ptr(action).to_str() else {
        return false;
    };
    let c = &*context;
    match action {
        "focus on" | "focus off" => {
            c.lock().unwrap().ui.automatic = action == "focus on";
            true
        }
        _ => command(c, action).is_ok(),
    }
}
