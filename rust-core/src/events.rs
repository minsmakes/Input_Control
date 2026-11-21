#[derive(Debug, Clone, PartialEq)]
pub enum Event {
    PointerMove(f32, f32),
    MouseButton(i32, bool),
    Scroll(f32, f32),
    KeyDown(i32),
    KeyUp(i32),
    TextComposing(char),
    TextCommit(char),
    TextCompositionCancel,
    MediaCommand(i32),
    Shortcut(i32),
}
#[derive(Debug)]
pub struct Record {
    pub sequence: u64,
    pub nanos: u64,
    pub event: Event,
}
