use std::{
    fs,
    io::{self, Write},
    path::Path,
    ptr,
    time::SystemTime,
};
use windows_sys::Win32::{
    Foundation::{CloseHandle, HANDLE, WAIT_ABANDONED, WAIT_OBJECT_0, WAIT_TIMEOUT},
    System::Threading::{CreateMutexW, ReleaseMutex, WaitForSingleObject},
};

extern "C" {
    fn ic_activate_existing();
}

// Local scopes the object to the interactive session. The creating token's
// default DACL applies. CreateMutex and the wait both run on the main thread.
pub struct Instance(HANDLE);
impl Instance {
    pub fn acquire() -> io::Result<Option<Self>> {
        let name: Vec<u16> = "Local\\InputControl.WindowsCompanion.Instance.v1\0"
            .encode_utf16()
            .collect();
        unsafe {
            let handle = CreateMutexW(ptr::null(), 0, name.as_ptr());
            if handle.is_null() {
                return Err(io::Error::last_os_error());
            }
            match WaitForSingleObject(handle, 0) {
                state @ (WAIT_OBJECT_0 | WAIT_ABANDONED) => {
                    log(if state == WAIT_ABANDONED {
                        "startup: recovered abandoned instance mutex"
                    } else {
                        "startup: instance mutex acquired"
                    });
                    Ok(Some(Self(handle)))
                }
                WAIT_TIMEOUT => {
                    CloseHandle(handle);
                    log("duplicate launch: activating existing window");
                    ic_activate_existing();
                    Ok(None)
                }
                _ => {
                    let e = io::Error::last_os_error();
                    CloseHandle(handle);
                    Err(e)
                }
            }
        }
    }
}
impl Drop for Instance {
    fn drop(&mut self) {
        unsafe {
            ReleaseMutex(self.0);
            CloseHandle(self.0);
        }
    }
}
pub fn log(message: &str) {
    let Some(base) = std::env::var_os("LOCALAPPDATA") else {
        return;
    };
    let dir = Path::new(&base).join("Input Control Receiver");
    if fs::create_dir_all(&dir).is_err() {
        return;
    }
    if let Ok(mut file) = fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(dir.join("lifecycle.log"))
    {
        let ts = SystemTime::now()
            .duration_since(SystemTime::UNIX_EPOCH)
            .unwrap_or_default()
            .as_millis();
        let _ = writeln!(file, "{ts} pid={} {message}", std::process::id());
    }
}
pub fn file_error(op: &str, path: &Path, e: io::Error) -> io::Error {
    let text = format!("{op} '{}': {e}", path.display());
    log(&text);
    io::Error::new(e.kind(), text)
}
