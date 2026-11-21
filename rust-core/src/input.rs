use crate::{
    events::{Event, Record},
    hangul::{two_set, Composer},
};
use std::{collections::BTreeSet, time::Instant};

pub struct Engine {
    start: Instant,
    pub count: u64,
    pub batch: Vec<Record>,
    pub keys: BTreeSet<i32>,
    pub buttons: BTreeSet<i32>,
    composer: Composer,
    korean: bool,
    pub last_kind: &'static str,
    pub last_ns: u64,
}
impl Default for Engine {
    fn default() -> Self {
        Self {
            start: Instant::now(),
            count: 0,
            batch: Vec::with_capacity(8),
            keys: Default::default(),
            buttons: Default::default(),
            composer: Default::default(),
            korean: false,
            last_kind: "Idle",
            last_ns: 0,
        }
    }
}
impl Engine {
    pub fn composing(&self) -> Option<char> {
        self.composer.current()
    }
    pub fn physical_key(&self, id: i32) -> bool {
        let shortcut = self.keys.iter().any(|k| matches!(k, 224 | 226 | 227));
        shortcut
            || match id {
                42 => self.composer.current().is_none(),
                40 | 41 | 43 | 58..=69 | 76 | 79..=82 | 88 | 224..=227 => true,
                _ => false,
            }
    }
    fn emit(&mut self, event: Event) {
        self.last_kind = match event {
            Event::PointerMove(..) => "PointerMove",
            Event::Scroll(..) => "Scroll",
            Event::MouseButton(1, false) => "LeftUp",
            Event::MouseButton(2, false) => "RightUp",
            Event::MouseButton(..) => "Button",
            Event::TextCommit(_) => "TextCommit",
            Event::TextComposing(_) => "Composing",
            _ => "Input",
        };
        self.count += 1;
        self.batch.push(Record {
            sequence: self.count,
            nanos: self.start.elapsed().as_nanos() as u64,
            event,
        });
    }
    fn commit(&mut self) {
        if let Some(c) = self.composer.finish() {
            self.emit(Event::TextCommit(c))
        }
    }
    // Key identifiers follow USB HID usage page 0x07. Text is a separate operation.
    pub fn dispatch(&mut self, op: i32, id: i32, x: f32, y: f32) -> Result<u64, &'static str> {
        use Event::*;
        self.batch.clear();
        let start = Instant::now();
        match op {
            1 => self.emit(PointerMove(x, y)),
            2 => self.emit(Scroll(x, y)),
            3 if self.buttons.insert(id) => {
                self.commit();
                self.emit(MouseButton(id, true));
            }
            4 if self.buttons.remove(&id) => self.emit(MouseButton(id, false)),
            5 if self.keys.insert(id) => {
                if !matches!(id, 225 | 42) && self.physical_key(id) {
                    self.commit()
                }
                self.emit(KeyDown(id))
            }
            6 if self.keys.remove(&id) => self.emit(KeyUp(id)),
            3..=6 => {}
            7 => {
                let c = char::from_u32(id as u32).ok_or("invalid scalar")?;
                if let Some(j) = self
                    .korean
                    .then(|| two_set(c.to_ascii_lowercase(), c.is_ascii_uppercase()))
                    .flatten()
                {
                    if let Some(done) = self.composer.push(j) {
                        self.emit(TextCommit(done))
                    }
                    if let Some(active) = self.composer.current() {
                        self.emit(TextComposing(active))
                    }
                } else {
                    self.commit();
                    self.emit(TextCommit(c))
                }
            }
            8 => {
                if self.composer.backspace() {
                    if let Some(c) = self.composer.current() {
                        self.emit(TextComposing(c))
                    } else {
                        self.emit(TextCompositionCancel)
                    }
                }
            }
            9 => {
                self.commit();
                self.korean = id != 0;
            }
            10 => {
                self.commit();
                self.emit(MediaCommand(id))
            }
            16 if id > 0 => self.emit(Shortcut(id)),
            11 => {
                self.commit();
                self.emit(Shortcut(id))
            }
            12 | 15 => {
                while let Some(k) = self.keys.pop_first() {
                    self.emit(KeyUp(k))
                }
                while let Some(b) = self.buttons.pop_first() {
                    self.emit(MouseButton(b, false))
                }
                if op == 12 && self.composer.finish().is_some() {
                    self.emit(TextCompositionCancel)
                }
            }
            13 => self.commit(),
            14 if (-8..=8).contains(&id) && id != 0 => {
                let borrow_ctrl = !self.keys.contains(&224);
                if borrow_ctrl {
                    self.emit(KeyDown(224));
                }
                self.emit(Scroll(0., id as f32 * 40.));
                if borrow_ctrl {
                    self.emit(KeyUp(224));
                }
                self.last_kind = "Zoom";
            }
            _ => return Err("invalid operation"),
        }
        self.last_ns = start.elapsed().as_nanos() as u64;
        Ok(self.last_ns)
    }
}
