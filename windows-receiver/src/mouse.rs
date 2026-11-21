use input_wifi::{err, RemoteInput, Result};
use std::collections::BTreeSet;
use windows_sys::Win32::UI::Input::KeyboardAndMouse::*;
pub trait Sink {
    fn inject(&mut self, m: RemoteInput) -> Result<()>;
}
#[derive(Default)]
pub struct Desktop {
    preedit: Option<(char, usize, u32)>,
}

fn target() -> usize {
    use windows_sys::Win32::UI::WindowsAndMessaging::*;
    unsafe {
        let mut info: GUITHREADINFO = std::mem::zeroed();
        info.cbSize = std::mem::size_of_val(&info) as u32;
        if GetGUIThreadInfo(0, &mut info) != 0 {
            info.hwndFocus as usize
        } else {
            0
        }
    }
}
fn input_stamp() -> u32 {
    let mut info = LASTINPUTINFO {
        cbSize: std::mem::size_of::<LASTINPUTINFO>() as u32,
        dwTime: 0,
    };
    unsafe { GetLastInputInfo(&mut info) };
    info.dwTime
}
impl Desktop {
    // The remote composer owns at most one BMP Hangul character. Never erase an
    // earlier target's text after a local keystroke, mouse click or focus change.
    fn render(&mut self, next: Option<char>, editing: bool) -> Result<()> {
        let owner = target();
        let old = self.preedit.take();
        let replace =
            old.is_some_and(|(_, hwnd, t)| owner != 0 && hwnd == owner && input_stamp() == t);
        if old.is_some_and(|(c, _, _)| Some(c) == next) && !editing {
            return Ok(());
        }
        if old.is_some() && !replace && !editing {
            return Ok(());
        }
        let mut input = [unsafe { std::mem::zeroed::<INPUT>() }; 6];
        let mut n = 0;
        if replace {
            input[0] = keyboard_input(0x0e, KEYEVENTF_SCANCODE, true);
            input[1] = keyboard_input(0x0e, KEYEVENTF_SCANCODE, false);
            n = 2;
        }
        if let Some(c) = next {
            let mut text = [unsafe { std::mem::zeroed::<INPUT>() }; 4];
            let units = unicode_inputs(c, &mut text);
            input[n..n + units].copy_from_slice(&text[..units]);
            n += units;
        }
        if n > 0
            && unsafe {
                SendInput(
                    n as u32,
                    input.as_ptr(),
                    std::mem::size_of::<INPUT>() as i32,
                )
            } != n as u32
        {
            return Err(err("Windows rejected composition input"));
        }
        if editing {
            self.preedit = next.map(|c| (c, owner, input_stamp()));
        }
        Ok(())
    }
}
fn windows_input(m: RemoteInput) -> Result<INPUT> {
    let (dx, dy, mouse_data, flags) = match m {
        RemoteInput::Move(x, y) => (x, y, 0, MOUSEEVENTF_MOVE),
        RemoteInput::Wheel(v) => (0, 0, v as u32, MOUSEEVENTF_WHEEL),
        RemoteInput::Button(1, true) => (0, 0, 0, MOUSEEVENTF_LEFTDOWN),
        RemoteInput::Button(1, false) => (0, 0, 0, MOUSEEVENTF_LEFTUP),
        RemoteInput::Button(2, true) => (0, 0, 0, MOUSEEVENTF_RIGHTDOWN),
        RemoteInput::Button(2, false) => (0, 0, 0, MOUSEEVENTF_RIGHTUP),
        _ => return Err(err("Invalid mouse operation")),
    };
    let input = INPUT {
        r#type: INPUT_MOUSE,
        Anonymous: INPUT_0 {
            mi: MOUSEINPUT {
                dx,
                dy,
                mouseData: mouse_data,
                dwFlags: flags,
                time: 0,
                dwExtraInfo: 0,
            },
        },
    };
    Ok(input)
}
impl Sink for Desktop {
    fn inject(&mut self, m: RemoteInput) -> Result<()> {
        match m {
            RemoteInput::Preedit(next) => return self.render(next, true),
            RemoteInput::Text(c) if self.preedit.is_some() => return self.render(Some(c), false),
            RemoteInput::Button(_, true) | RemoteInput::Key(_, true) => self.preedit = None,
            _ => {}
        }
        let mut inputs = [unsafe { std::mem::zeroed::<INPUT>() }; 4];
        let count = match m {
            RemoteInput::Key(k, down) => {
                let (scan, extended) = scan_code(k).ok_or_else(|| err("Unknown keyboard usage"))?;
                inputs[0] = keyboard_input(
                    scan,
                    KEYEVENTF_SCANCODE | if extended { KEYEVENTF_EXTENDEDKEY } else { 0 },
                    down,
                );
                1
            }
            RemoteInput::Text(c) => unicode_inputs(c, &mut inputs),
            _ => {
                inputs[0] = windows_input(m)?;
                1
            }
        };
        let sent =
            unsafe { SendInput(count as u32, inputs.as_ptr(), size_of::<INPUT>() as i32) } as usize;
        if sent != count {
            if matches!(m, RemoteInput::Text(_)) && sent % 2 == 1 {
                unsafe { SendInput(1, &inputs[sent], size_of::<INPUT>() as i32) };
            }
            return Err(err(
                "Windows rejected SendInput. Check desktop and application integrity level.",
            ));
        }
        Ok(())
    }
}
fn keyboard_input(scan: u16, flags: u32, down: bool) -> INPUT {
    INPUT {
        r#type: INPUT_KEYBOARD,
        Anonymous: INPUT_0 {
            ki: KEYBDINPUT {
                wVk: 0,
                wScan: scan,
                dwFlags: flags | if down { 0 } else { KEYEVENTF_KEYUP },
                time: 0,
                dwExtraInfo: 0,
            },
        },
    }
}
fn unicode_inputs(c: char, out: &mut [INPUT; 4]) -> usize {
    let mut units = [0; 2];
    let utf16 = c.encode_utf16(&mut units);
    for (i, unit) in utf16.iter().enumerate() {
        out[2 * i] = keyboard_input(*unit, KEYEVENTF_UNICODE, true);
        out[2 * i + 1] = keyboard_input(*unit, KEYEVENTF_UNICODE, false);
    }
    utf16.len() * 2
}
// USB keyboard usages to PC set-1 make codes. E0 selects the extended key.
fn scan_code(k: u8) -> Option<(u16, bool)> {
    const LETTERS: [u16; 26] = [
        0x1e, 0x30, 0x2e, 0x20, 0x12, 0x21, 0x22, 0x23, 0x17, 0x24, 0x25, 0x26, 0x32, 0x31, 0x18,
        0x19, 0x10, 0x13, 0x1f, 0x14, 0x16, 0x2f, 0x11, 0x2d, 0x15, 0x2c,
    ];
    let scan = match k {
        4..=29 => LETTERS[(k - 4) as usize],
        30..=39 => u16::from(k - 28),
        40..=49 => [0x1c, 1, 0x0e, 0x0f, 0x39, 0x0c, 0x0d, 0x1a, 0x1b, 0x2b][(k - 40) as usize],
        51 => 0x27,
        52 => 0x28,
        53 => 0x29,
        76 => 0x53,
        54..=56 => u16::from(k - 3),
        58..=67 => u16::from(k + 1),
        68 => 0x57,
        69 => 0x58,
        79..=82 => [0x4d, 0x4b, 0x50, 0x48][(k - 79) as usize],
        84..=99 => [
            0x35, 0x37, 0x4a, 0x4e, 0x1c, 0x4f, 0x50, 0x51, 0x4b, 0x4c, 0x4d, 0x47, 0x48, 0x49,
            0x52, 0x53,
        ][(k - 84) as usize],
        224..=227 => [0x1d, 0x2a, 0x38, 0x5b][(k - 224) as usize],
        _ => return None,
    };
    Some((scan, matches!(k, 76 | 79..=82 | 84 | 88 | 227)))
}
pub struct HeldInput<S: Sink> {
    sink: S,
    held: [bool; 2],
    keys: BTreeSet<u8>,
}
impl<S: Sink> HeldInput<S> {
    pub fn new(sink: S) -> Self {
        Self {
            sink,
            held: [false; 2],
            keys: BTreeSet::new(),
        }
    }
    pub fn apply(&mut self, m: RemoteInput) -> Result<()> {
        match m {
            RemoteInput::Release => {
                while let Some(k) = self.keys.first().copied() {
                    self.apply(RemoteInput::Key(k, false))?;
                }
                for b in 1..=2 {
                    if self.held[b - 1] {
                        self.apply(RemoteInput::Button(b as u8, false))?
                    }
                }
            }
            RemoteInput::Button(b @ 1..=2, p) => {
                let idx = b as usize - 1;
                if self.held[idx] != p {
                    self.sink.inject(m)?;
                    self.held[idx] = p
                }
            }
            RemoteInput::Key(k, down) => {
                if self.keys.contains(&k) != down {
                    self.sink.inject(m)?;
                    if down {
                        self.keys.insert(k);
                    } else {
                        self.keys.remove(&k);
                    }
                }
            }
            _ => self.sink.inject(m)?,
        }
        Ok(())
    }
}
impl<S: Sink> Drop for HeldInput<S> {
    fn drop(&mut self) {
        for k in &self.keys {
            if let Err(e) = self.sink.inject(RemoteInput::Key(*k, false)) {
                eprintln!("Key release failed: {e}")
            }
        }
        for b in 1..=2 {
            if self.held[b - 1] {
                if let Err(e) = self.sink.inject(RemoteInput::Button(b as u8, false)) {
                    eprintln!("Release failed: {e}")
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn keyboard_packets() {
        for k in 0..=255 {
            assert_eq!(scan_code(k).is_some(), input_wifi::supported_key(k as i32));
        }
        assert_eq!(scan_code(6), Some((0x2e, false)));
        assert_eq!(scan_code(79), Some((0x4d, true)));
        for c in ['한', '글', '🙂'] {
            let mut out = [unsafe { std::mem::zeroed::<INPUT>() }; 4];
            let n = unicode_inputs(c, &mut out);
            let expected: Vec<_> = c.to_string().encode_utf16().collect();
            assert_eq!(n, expected.len() * 2);
            for (i, unit) in expected.iter().enumerate() {
                for p in 0..2 {
                    let ki = unsafe { out[i * 2 + p].Anonymous.ki };
                    assert_eq!(ki.wScan, *unit);
                    assert_eq!(
                        ki.dwFlags,
                        KEYEVENTF_UNICODE | if p == 1 { KEYEVENTF_KEYUP } else { 0 }
                    );
                }
            }
        }
    }
    #[test]
    fn windows_relative_button_and_wheel_flags() {
        for (event, flag, x, y, data) in [
            (RemoteInput::Move(-4, 7), MOUSEEVENTF_MOVE, -4, 7, 0),
            (RemoteInput::Button(1, true), MOUSEEVENTF_LEFTDOWN, 0, 0, 0),
            (RemoteInput::Button(1, false), MOUSEEVENTF_LEFTUP, 0, 0, 0),
            (RemoteInput::Button(2, true), MOUSEEVENTF_RIGHTDOWN, 0, 0, 0),
            (RemoteInput::Button(2, false), MOUSEEVENTF_RIGHTUP, 0, 0, 0),
            (RemoteInput::Wheel(-120), MOUSEEVENTF_WHEEL, 0, 0, -120),
        ] {
            let packet = windows_input(event).unwrap();
            assert_eq!(packet.r#type, INPUT_MOUSE);
            let mi = unsafe { packet.Anonymous.mi };
            assert_eq!(
                (mi.dwFlags, mi.dx, mi.dy, mi.mouseData as i32),
                (flag, x, y, data)
            );
        }
    }
}
