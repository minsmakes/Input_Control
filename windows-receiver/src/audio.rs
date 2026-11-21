use input_wifi::{Link, Result, AUDIO_OUTPUT, AUDIO_STATE};
use std::ffi::c_void;
extern "C" {
    fn ic_audio_open() -> *mut c_void;
    fn ic_audio_close(p: *mut c_void);
    fn ic_audio_state(p: *mut c_void, vol: *mut u8, mute: *mut u8) -> i32;
    fn ic_audio_label(p: *mut c_void, i: i32, buf: *mut u16, cap: i32, current: *mut bool) -> i32;
    fn ic_audio_command(p: *mut c_void, cmd: i32);
}
pub struct Audio(*mut c_void);
impl Audio {
    pub fn new() -> Self {
        Self(unsafe { ic_audio_open() })
    }
    pub fn command(&mut self, cmd: u8) {
        unsafe { ic_audio_command(self.0, cmd.into()) }
    }
    pub fn publish(&mut self, link: &mut Link) -> Result<()> {
        let (mut vol, mut mute) = (255, 0);
        let count = unsafe { ic_audio_state(self.0, &mut vol, &mut mute) };
        if count < 0 {
            return Ok(());
        }
        link.send(&[AUDIO_STATE, vol, mute, count as u8])?;
        for i in 0..count {
            let mut name = [0; 56];
            let mut current = false;
            let len = unsafe { ic_audio_label(self.0, i, name.as_mut_ptr(), 56, &mut current) };
            let mut frame = vec![AUDIO_OUTPUT, current as u8];
            let label =
                String::from_utf16_lossy(&name[..len as usize]).replace(char::is_control, " ");
            frame.extend_from_slice(if label.is_empty() {
                b"Audio output"
            } else {
                label.as_bytes()
            });
            link.send(&frame)?;
        }
        Ok(())
    }
}
impl Drop for Audio {
    fn drop(&mut self) {
        unsafe { ic_audio_close(self.0) }
    }
}
