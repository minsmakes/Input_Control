use input_control::{
    events::{Event, Record},
    input::Engine as Core,
};
#[derive(Default)]
struct Engine {
    core: Core,
    history: Vec<Record>,
    committed: String,
    composing: Option<char>,
}
impl std::ops::Deref for Engine {
    type Target = Core;
    fn deref(&self) -> &Core {
        &self.core
    }
}
impl Engine {
    fn preview(&self) -> String {
        self.committed.chars().chain(self.composing).collect()
    }
    fn dispatch(&mut self, op: i32, id: i32, x: f32, y: f32) -> Result<u64, &'static str> {
        let elapsed = self.core.dispatch(op, id, x, y)?;
        if op == 8 && self.core.batch.is_empty() {
            self.committed.pop();
        }
        for r in self.core.batch.drain(..) {
            match r.event {
                Event::TextCommit(c) => {
                    self.committed.push(c);
                    self.composing = None
                }
                Event::TextComposing(c) => self.composing = Some(c),
                Event::TextCompositionCancel => self.composing = None,
                _ => {}
            }
            self.history.push(r);
        }
        Ok(elapsed)
    }
}
fn run(e: &mut Engine, op: i32, id: i32) {
    e.dispatch(op, id, 0., 0.).unwrap();
}
fn type_keys(e: &mut Engine, s: &str) {
    for c in s.chars() {
        run(e, 7, c as i32);
    }
}
fn korean(s: &str) -> Engine {
    let mut e = Engine::default();
    run(&mut e, 9, 1);
    type_keys(&mut e, s);
    e
}

#[test]
fn required_korean_examples() {
    for (keys, want) in [
        ("gks", "한"),
        ("rkatkgkqslek", "감사합니다"),
        ("gksrnrdj dlqfur xptmxm", "한국어 입력 테스트"),
        ("rmf", "글"),
        ("dkssudgktpdy", "안녕하세요"),
        ("tkatjd roffjrtl", "삼성 갤럭시"),
    ] {
        let mut e = korean(keys);
        assert_eq!(e.preview(), want);
        run(&mut e, 13, 0);
        assert_eq!(e.committed, want);
    }
}
#[test]
fn release_holds_preserves_active_syllable() {
    let mut e = korean("gk");
    run(&mut e, 15, 0);
    assert_eq!(e.core.composing(), Some('하'));
    type_keys(&mut e, "s");
    assert_eq!(e.preview(), "한");
    run(&mut e, 3, 1);
    assert_eq!(e.committed, "한");
    assert_eq!(e.core.composing(), None);
}

#[test]
fn mixed_language_commit_once() {
    let mut e = korean("gksrmf");
    run(&mut e, 9, 0);
    run(&mut e, 9, 0);
    type_keys(&mut e, " English 123");
    assert_eq!(e.preview(), "한글 English 123");
    assert_eq!(
        e.history
            .iter()
            .filter(|r| r.event == Event::TextCommit('글'))
            .count(),
        1
    );
}
#[test]
fn vowels_clusters_and_split() {
    for (keys, want) in [
        ("rhk", "과"),
        ("rhos", "괜"),
        ("rnj", "궈"),
        ("rnl", "귀"),
        ("dml", "의"),
        ("rkqt", "값"),
        ("rkqtdl", "값이"),
        ("rkqtk", "갑사"),
        ("ekfr", "닭"),
        ("ekfrk", "달가"),
        ("Rk", "까"),
        ("rhkT", "괐"),
        ("rkRk", "가까"),
    ] {
        assert_eq!(korean(keys).preview(), want, "{keys}");
    }
}
#[test]
fn backspace_decomposes_one_step() {
    let mut e = korean("rhkrt");
    for want in ["곽", "과", "고", "ㄱ", ""] {
        run(&mut e, 8, 0);
        assert_eq!(e.preview(), want);
    }
    assert_eq!(
        e.history.last().unwrap().event,
        Event::TextCompositionCancel
    );
}
#[test]
fn spaces_enter_and_unicode() {
    let mut e = korean("gks rmf\n");
    run(&mut e, 9, 0);
    type_keys(&mut e, "🙂");
    assert_eq!(e.preview(), "한 글\n🙂");
    run(&mut e, 8, 0);
    assert_eq!(e.preview(), "한 글\n");
}
#[test]
fn cancellation_releases_each_held_input_once() {
    let mut e = korean("");
    for (op, id) in [
        (5, 225),
        (5, 225),
        (5, 4),
        (3, 1),
        (3, 1),
        (7, 'g' as i32),
        (12, 0),
        (12, 0),
    ] {
        run(&mut e, op, id)
    }
    assert!(e.keys.is_empty() && e.buttons.is_empty());
    assert_eq!(e.preview(), "");
    for event in [
        Event::KeyDown(225),
        Event::KeyUp(225),
        Event::KeyUp(4),
        Event::MouseButton(1, true),
        Event::MouseButton(1, false),
        Event::TextCompositionCancel,
    ] {
        assert_eq!(e.history.iter().filter(|r| r.event == event).count(), 1);
    }
}
#[test]
fn fifo_sequence_and_monotonic_clock() {
    let mut e = Engine::default();
    for (op, id) in [(5, 4), (7, 97), (6, 4), (3, 2), (4, 2), (10, 2), (11, 1)] {
        run(&mut e, op, id)
    }
    let expected = [
        Event::KeyDown(4),
        Event::TextCommit('a'),
        Event::KeyUp(4),
        Event::MouseButton(2, true),
        Event::MouseButton(2, false),
        Event::MediaCommand(2),
        Event::Shortcut(1),
    ];
    let mut previous = 0;
    for (i, r) in e.history.iter().enumerate() {
        assert_eq!(r.event, expected[i]);
        assert_eq!(r.sequence, i as u64 + 1);
        assert!(r.nanos >= previous);
        previous = r.nanos;
    }
}
#[test]
fn bounded_history_preserves_state() {
    let mut e = Engine::default();
    run(&mut e, 3, 1);
    for _ in 0..500 {
        e.dispatch(1, 0, 3., 1.).unwrap();
    }
    run(&mut e, 4, 1);
    assert!(e.buttons.is_empty());
    assert!(e.core.batch.is_empty());
    assert_eq!(e.count, 502);
    type_keys(&mut e, &"x".repeat(100));
    assert!(e.core.batch.is_empty());
    assert!(e.dispatch(7, 0xd800, 0., 0.).is_err());
}

#[test]
fn zoom_borrows_ctrl_for_exactly_one_wheel_operation() {
    let mut engine = Core::default();
    for step in [-1, 1] {
        engine.dispatch(14, step, 0., 0.).unwrap();
        assert_eq!(
            engine
                .batch
                .iter()
                .map(|r| r.event.clone())
                .collect::<Vec<_>>(),
            [
                Event::KeyDown(224),
                Event::Scroll(0., step as f32 * 40.),
                Event::KeyUp(224)
            ]
        );
    }
    engine.dispatch(5, 224, 0., 0.).unwrap();
    engine.dispatch(14, 1, 0., 0.).unwrap();
    assert_eq!(engine.batch.len(), 1);
    assert!(engine.keys.contains(&224));
    engine.dispatch(12, 0, 0., 0.).unwrap();
    assert!(engine.keys.is_empty());
}
