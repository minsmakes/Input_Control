use crate::events::Event;
use input_wifi::*;
use std::{
    io,
    net::{Shutdown, SocketAddr, TcpStream},
    sync::{
        mpsc::{self, Receiver, SyncSender, TrySendError},
        Arc, LazyLock, Mutex,
    },
    thread,
    time::{Duration, Instant},
};
#[derive(Clone, Copy)]
enum Action {
    Keyboard(RemoteInput),
    Move(f32, f32),
    Wheel(f32),
    Button(u8, bool),
    Release,
}
struct State {
    phase: &'static str,
    name: String,
    peer: String,
    code: String,
    error: String,
    sent: u64,
    received: u64,
    rtt: u64,
    work: u64,
    socket: Option<TcpStream>,
    approved: bool,
    closed: bool,
    focus: u64,
    modern: bool,
    dashboard: bool,
    changes: [Option<String>; 2],
    audio: String,
}
struct Session {
    id: i64,
    state: Mutex<State>,
    tx: SyncSender<Action>,
}
static CURRENT: LazyLock<Mutex<Option<Arc<Session>>>> = LazyLock::new(Default::default);
impl Session {
    fn stop(&self, reason: &str) {
        let mut s = self.state.lock().unwrap();
        s.closed = true;
        s.phase = if reason.is_empty() {
            "Disconnected"
        } else {
            "Failed"
        };
        s.error = reason.into();
        if let Some(sock) = s.socket.take() {
            let _ = sock.shutdown(Shutdown::Both);
        }
    }
}
pub fn close() {
    if let Some(s) = CURRENT.lock().unwrap().take() {
        s.stop("")
    }
}
pub static WANT_FOCUS: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(true);
pub fn start(
    id: i64,
    addr: SocketAddr,
    key: [u8; 32],
    expected: Option<[u8; 32]>,
    model: String,
) -> Result<()> {
    if id <= 0 {
        return Err(err("Invalid session"));
    }
    close();
    let (tx, rx) = mpsc::sync_channel(96);
    let s = Arc::new(Session {
        id,
        tx,
        state: Mutex::new(State {
            phase: "Connecting",
            name: String::new(),
            peer: String::new(),
            code: String::new(),
            error: String::new(),
            sent: 0,
            received: 0,
            rtt: 0,
            work: 0,
            socket: None,
            approved: false,
            closed: false,
            focus: 0,
            modern: false,
            dashboard: false,
            changes: Default::default(),
            audio: String::new(),
        }),
    });
    *CURRENT.lock().unwrap() = Some(s.clone());
    thread::spawn(move || {
        if let Err(e) = run(&s, addr, key, expected, rx, &model) {
            let closed = s.state.lock().unwrap().closed;
            if !closed {
                s.stop(&e.to_string());
            }
        }
    });
    Ok(())
}
pub fn approve(id: i64, code: &str) {
    let current = CURRENT.lock().unwrap();
    if let Some(session) = current.as_ref().filter(|s| s.id == id) {
        let mut s = session.state.lock().unwrap();
        if s.phase == "Pairing" && !s.closed {
            if s.code
                .get(24..)
                .is_some_and(|c| c.eq_ignore_ascii_case(code))
            {
                s.approved = true
            } else {
                drop(s);
                session.stop("Incorrect verification code. Start pairing again.");
            }
        }
    }
}
pub fn dashboard(id: i64, kind: usize) -> Option<String> {
    let current = CURRENT.lock().ok()?;
    let session = current.as_ref().filter(|s| s.id == id)?;
    let mut state = session.state.lock().ok()?;
    state.changes.get_mut(kind)?.take()
}
pub fn status() -> String {
    let current = CURRENT.lock().unwrap();
    let Some(session) = current.as_ref() else {
        return "Disconnected".into();
    };
    let s = session.state.lock().unwrap();
    format!(
        "{}\n{}\n{}\n{}\n{}\n{}\n{}\n{}\n{}\n{}\n{}",
        s.phase,
        s.name,
        s.peer,
        s.code.get(..24).unwrap_or(""),
        s.error,
        s.sent,
        s.received,
        s.rtt,
        s.work,
        s.focus,
        s.audio
    )
}
pub fn forward(id: i64, event: Option<&Event>, cancel: bool) {
    if id == 0 {
        return;
    }
    let action = if cancel {
        Action::Release
    } else {
        match event {
            Some(Event::Shortcut(id)) if *id > 0 => Action::Keyboard(RemoteInput::Launch(*id)),
            Some(Event::TextComposing(c)) => Action::Keyboard(RemoteInput::Preedit(Some(*c))),
            Some(Event::TextCompositionCancel) => Action::Keyboard(RemoteInput::Preedit(None)),
            Some(Event::MediaCommand(a @ (0..=7 | 100..=200))) => {
                Action::Keyboard(RemoteInput::Audio(*a as u8))
            }
            Some(Event::PointerMove(x, y)) => Action::Move(*x, *y),
            Some(Event::Scroll(_, y)) => Action::Wheel(*y),
            Some(Event::MouseButton(b @ 1..=2, p)) => Action::Button(*b as u8, *p),
            Some(Event::KeyDown(k) | Event::KeyUp(k)) if supported_key(*k) => Action::Keyboard(
                RemoteInput::Key(*k as u8, matches!(event, Some(Event::KeyDown(_)))),
            ),
            Some(Event::TextCommit(c)) if !c.is_control() => {
                Action::Keyboard(RemoteInput::Text(*c))
            }
            _ => return,
        }
    };
    let current = CURRENT.lock().unwrap();
    if let Some(session) = current.as_ref().filter(|s| s.id == id) {
        let state = session.state.lock().unwrap();
        if (!state.dashboard && matches!(action, Action::Keyboard(RemoteInput::Launch(_))))
            || state.phase != "Connected"
            || !state.modern
                && matches!(
                    action,
                    Action::Keyboard(RemoteInput::Audio(_) | RemoteInput::Preedit(_))
                )
        {
            return;
        }
        drop(state);
        if let Err(TrySendError::Full(_)) = session.tx.try_send(action) {
            session.stop("Network queue full. Disconnected to release held buttons.")
        }
    }
}
fn run(
    session: &Session,
    addr: SocketAddr,
    key: [u8; 32],
    expected: Option<[u8; 32]>,
    rx: Receiver<Action>,
    model: &str,
) -> Result<()> {
    let dial = || -> Result<(TcpStream, Link)> {
        let socket = TcpStream::connect_timeout(&addr, Duration::from_secs(5)).map_err(|e| {
            if e.kind() == io::ErrorKind::PermissionDenied {
                return err("Local network permission denied. Check Android app permissions.");
            }
            err(format!(
                "Receiver unavailable at {addr}. Check the receiver, Wi-Fi and firewall ({e})."
            ))
        })?;
        {
            let mut s = session.state.lock().unwrap();
            if s.closed {
                return Err(err("Cancelled"));
            }
            s.socket = Some(socket.try_clone()?);
        }
        let probe = socket.try_clone()?;
        let link = Link::open(socket, &key, true)?;
        if expected.is_some_and(|k| k != link.peer) {
            return Err(err("Authentication failed: receiver identity changed."));
        }
        Ok((probe, link))
    };
    let (mut probe, mut link) = dial()?;
    let mut capable = false;
    let mut modern = false;
    let mut dashboard = false;
    if !model.is_empty() {
        for hello in [
            &[FOCUS_HELLO, 3][..],
            &[FOCUS_HELLO, 2][..],
            &[FOCUS_HELLO][..],
        ] {
            let mut response = [0; 256];
            link.send(hello)?;
            match link.receive(&mut response, Instant::now() + Duration::from_secs(3)) {
                Ok(n) if &response[..n] == hello => {
                    capable = true;
                    modern = n == 2;
                    dashboard = hello == [FOCUS_HELLO, 3];
                    break;
                }
                Err(_) => {
                    drop(link);
                    drop(probe);
                    (probe, link) = dial()?;
                }
                _ => return Err(err("Invalid protocol negotiation")),
            }
        }
    }
    link.send(&[if expected.is_some() { CONNECT } else { PAIR }])?;
    let mut buf = [0; 256];
    let mut n = link.receive(&mut buf, Instant::now() + Duration::from_secs(6))?;
    if expected.is_none() && &buf[..n] == [VERIFY] {
        let until = Instant::now() + TTL;
        {
            let mut s = session.state.lock().unwrap();
            if s.closed {
                return Ok(());
            }
            s.phase = "Pairing";
            s.code = link.verification.clone();
        }
        probe.set_read_timeout(Some(Duration::from_millis(100)))?;
        loop {
            if Instant::now() >= until {
                return Err(err("Pairing timed out"));
            }
            {
                let s = session.state.lock().unwrap();
                if s.closed {
                    return Ok(());
                }
                if s.approved {
                    break;
                }
            }
            match probe.peek(&mut [0]) {
                Ok(_) => return Err(err("Pairing rejected or receiver closed")),
                Err(e)
                    if matches!(
                        e.kind(),
                        io::ErrorKind::TimedOut | io::ErrorKind::WouldBlock
                    ) => {}
                Err(e) => return Err(e),
            }
        }
        link.send(&[CONFIRM])?;
        n = link.receive(&mut buf, until)?;
    } else if expected.is_none() {
        return Err(err("Pairing unavailable or attempt limit reached"));
    }
    if &buf[..n] == [UNTRUSTED] {
        return Err(err(
            "Authentication failed: tablet trust revoked. Forget this receiver and pair again.",
        ));
    }
    if n < 2 || n > 64 || buf[0] != READY {
        return Err(err("Pairing rejected or receiver unavailable"));
    }
    let name = std::str::from_utf8(&buf[1..n]).map_err(err)?;
    if !name.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-') {
        return Err(err("Invalid PC name"));
    }
    {
        let mut s = session.state.lock().unwrap();
        if s.closed {
            return Ok(());
        }
        s.phase = "Connected";
        s.modern = modern;
        s.dashboard = dashboard;
        s.name = name.into();
        s.peer = hexkey(&link.peer);
        s.code.clear();
    }
    let mut seq = 0u64;
    let mut chunks: [Vec<u8>; 2] = Default::default();
    let mut residual = [0f32; 3];
    let mut next = Instant::now();
    let mut deferred = None;
    loop {
        if session.state.lock().unwrap().closed {
            return Ok(());
        }
        if Instant::now() >= next {
            let t = Instant::now();
            let mut ping = [0; 66];
            ping[0] = FOCUS_PING;
            ping[1] = WANT_FOCUS.load(std::sync::atomic::Ordering::Relaxed) as u8;
            let label = model.as_bytes();
            let label = &label[..label.len().min(64)];
            ping[2..2 + label.len()].copy_from_slice(label);
            link.send(if capable {
                &ping[..2 + label.len()]
            } else {
                &[PING]
            })?;
            let until = t + Duration::from_secs(3);
            let mut n = link.receive(&mut buf, until)?;
            while dashboard && n >= 4 && buf[0] == DASHBOARD {
                let kind = buf[1] as usize;
                let part = chunks
                    .get_mut(kind)
                    .ok_or_else(|| err("Invalid dashboard type"))?;
                if buf[2] > 1 || part.len() + n - 3 > 1024 * 1024 {
                    return Err(err("Invalid dashboard update"));
                }
                part.extend_from_slice(&buf[3..n]);
                if buf[2] == 1 {
                    let data = String::from_utf8(std::mem::take(part)).map_err(err)?;
                    session.state.lock().unwrap().changes[kind] = Some(data);
                }
                n = link.receive(&mut buf, until)?;
            }
            if modern && n == 4 && buf[0] == AUDIO_STATE {
                let (vol, mute, count) = (buf[1], buf[2], buf[3]);
                if (vol > 100 && vol != 255) || mute > 1 || count > 32 {
                    return Err(err("Invalid audio state"));
                }
                let mut summary = format!("{vol} {mute}");
                for _ in 0..count {
                    let len = link.receive(&mut buf, until)?;
                    if len < 3 || buf[0] != AUDIO_OUTPUT || buf[1] > 1 {
                        return Err(err("Invalid audio endpoint"));
                    }
                    let name = std::str::from_utf8(&buf[2..len]).map_err(err)?;
                    if name.chars().any(char::is_control) {
                        return Err(err("Invalid endpoint label"));
                    }
                    summary.push('\n');
                    summary.push(if buf[1] == 1 { '*' } else { '-' });
                    summary.push_str(name);
                }
                session.state.lock().unwrap().audio = summary;
                n = link.receive(&mut buf, until)?;
            }
            if n != (if capable { 22 } else { 17 }) || buf[0] != PONG {
                return Err(err("Invalid receiver acknowledgment"));
            }
            let mut s = session.state.lock().unwrap();
            if capable {
                let focus = Focus::try_from(buf[17])?;
                let generation = u32::from_be_bytes(buf[18..22].try_into().unwrap());
                s.focus = (u64::from(generation) << 8) | focus as u64;
            }
            s.sent = seq;
            s.received = u64::from_be_bytes(buf[1..9].try_into().unwrap());
            s.work = u64::from_be_bytes(buf[9..17].try_into().unwrap());
            s.rtt = t.elapsed().as_micros() as u64;
            next = t + Duration::from_millis(if capable { 250 } else { 1000 });
        }
        let a = if let Some(event) = deferred.take() {
            event
        } else {
            match rx.recv_timeout(
                next.saturating_duration_since(Instant::now())
                    .min(Duration::from_millis(100)),
            ) {
                Ok(a) => a,
                Err(mpsc::RecvTimeoutError::Timeout) => continue,
                Err(_) => return Ok(()),
            }
        };
        // Collapse adjacent motion waiting behind a network write. Retain the first
        // non-motion operation for the next iteration so button edges stay ordered.
        let a = if let Action::Move(mut x, mut y) = a {
            for _ in 0..95 {
                match rx.try_recv() {
                    Ok(Action::Move(dx, dy)) => {
                        x += dx;
                        y += dy
                    }
                    Ok(other) => {
                        deferred = Some(other);
                        break;
                    }
                    Err(_) => break,
                }
            }
            Action::Move(x, y)
        } else {
            a
        };
        let mut quantize = |i: usize, v: f32| {
            let value = (v + residual[i]).clamp(-32767., 32767.);
            let steps = value.trunc();
            residual[i] = value - steps;
            steps as i32
        };
        let m = match a {
            Action::Keyboard(k) => k,
            Action::Move(x, y) => RemoteInput::Move(quantize(0, x), quantize(1, y)),
            Action::Wheel(y) => RemoteInput::Wheel(quantize(2, y * 3.)),
            Action::Button(b, p) => RemoteInput::Button(b, p),
            Action::Release => RemoteInput::Release,
        };
        if matches!(m, RemoteInput::Move(0, 0) | RemoteInput::Wheel(0)) {
            continue;
        }
        seq += 1;
        link.send(&m.encode(seq))?;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::TcpListener;

    fn await_phase(phase: &str) {
        let end = Instant::now() + Duration::from_secs(3);
        while status().lines().next() != Some(phase) {
            assert!(Instant::now() < end, "state: {}", status());
            thread::sleep(Duration::from_millis(5));
        }
    }

    #[test]
    fn changed_receiver_and_wrong_verifier_fail_closed() {
        for changed_pin in [true, false] {
            let listener = TcpListener::bind("127.0.0.1:0").unwrap();
            let addr = listener.local_addr().unwrap();
            let server = thread::spawn(move || {
                let (socket, _) = listener.accept().unwrap();
                let mut link = Link::open(socket, &secret().unwrap(), false).unwrap();
                let mut b = [0; 256];
                if !changed_pin {
                    let n = link
                        .receive(&mut b, Instant::now() + Duration::from_secs(3))
                        .unwrap();
                    assert_eq!(&b[..n], [PAIR]);
                    link.send(&[VERIFY]).unwrap();
                }
                assert!(link
                    .receive(&mut b, Instant::now() + Duration::from_secs(3))
                    .is_err());
            });
            start(
                42,
                addr,
                secret().unwrap(),
                changed_pin.then_some([0; 32]),
                String::new(),
            )
            .unwrap();
            if !changed_pin {
                await_phase("Pairing");
                approve(42, "INVALID!");
            }
            await_phase("Failed");
            assert!(status().contains(if changed_pin {
                "identity changed"
            } else {
                "Incorrect verification"
            }));
            close();
            assert_eq!(status(), "Disconnected");
            server.join().unwrap();
        }
    }
}
