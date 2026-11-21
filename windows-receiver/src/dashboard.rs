use input_wifi::{Link, Result, DASHBOARD};
use std::ffi::c_void;
extern "C" {
    fn dashboard_open() -> *mut c_void;
    fn dashboard_close(p: *mut c_void);
    fn dashboard_launch(p: *mut c_void, id: i32);
    fn dashboard_take(p: *mut c_void, kind: i32, buf: *mut u8, len: usize) -> usize;
}
#[derive(Default)]
struct Update {
    bytes: Vec<u8>,
    offset: usize,
}
pub struct Dashboard {
    native: *mut c_void,
    updates: [Update; 2],
    scratch: Vec<u8>,
}
impl Dashboard {
    pub fn new() -> Self {
        Self {
            native: unsafe { dashboard_open() },
            updates: Default::default(),
            scratch: vec![0; 4096],
        }
    }
    pub fn launch(&self, id: i32) {
        unsafe { dashboard_launch(self.native, id) }
    }
    pub fn publish(&mut self, link: &mut Link) -> Result<()> {
        for (kind, update) in self.updates.iter_mut().enumerate() {
            if update.offset == update.bytes.len() {
                let mut n = unsafe {
                    dashboard_take(
                        self.native,
                        kind as i32,
                        self.scratch.as_mut_ptr(),
                        self.scratch.len(),
                    )
                };
                if n > self.scratch.len() && n <= 1024 * 1024 {
                    self.scratch.resize(n, 0);
                    n = unsafe {
                        dashboard_take(
                            self.native,
                            kind as i32,
                            self.scratch.as_mut_ptr(),
                            self.scratch.len(),
                        )
                    };
                }
                if n > 0 && n <= self.scratch.len() && self.scratch[..n] != update.bytes {
                    update.bytes.clear();
                    update.bytes.extend_from_slice(&self.scratch[..n]);
                    update.offset = 0;
                }
            }
            for _ in 0..48 {
                if update.offset == update.bytes.len() {
                    break;
                }
                let end = (update.offset + 220).min(update.bytes.len());
                let mut frame = vec![DASHBOARD, kind as u8, (end == update.bytes.len()) as u8];
                frame.extend_from_slice(&update.bytes[update.offset..end]);
                link.send(&frame)?;
                update.offset = end;
            }
        }
        Ok(())
    }
}
impl Drop for Dashboard {
    fn drop(&mut self) {
        unsafe { dashboard_close(self.native) }
    }
}
