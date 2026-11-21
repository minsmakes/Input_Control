#![windows_subsystem = "windows"]
mod audio;
mod companion;
mod dashboard;
mod lifecycle;
mod mouse;
mod storage;
use input_wifi::*;
use mdns_sd::{ServiceDaemon, ServiceInfo};
use mouse::{Desktop, HeldInput};
use std::{
    collections::VecDeque,
    io::{self},
    net::{Ipv4Addr, Shutdown, TcpListener, TcpStream, UdpSocket},
    sync::{
        atomic::{AtomicBool, Ordering},
        mpsc, Arc, Mutex,
    },
    thread,
    time::{Duration, Instant},
};
use storage::Store;
struct Prompt {
    tag: String,
    answer: mpsc::SyncSender<bool>,
    expires: Instant,
    verification: String,
}
struct Console {
    store: Store,
    prompt: Option<Prompt>,
    socket: Option<TcpStream>,
    stop: bool,
    revision: u64,
    ui: companion::Status,
}
type Shared = Arc<Mutex<Console>>;
fn command(shared: &Shared, line: &str) -> Result<()> {
    let mut c = shared.lock().unwrap();
    let args: Vec<_> = line.split_whitespace().collect();
    match args.as_slice() {
        ["quit"] => {
            if c.stop {
                return Ok(());
            }
            lifecycle::log("shutdown requested: closing session");
            c.revision += 1;
            c.stop = true;
            c.prompt.take();
            if let Some(s) = &c.socket {
                let _ = s.shutdown(Shutdown::Both);
            }
        }
        ["disconnect"] => {
            c.revision += 1;
            c.prompt.take();
            if let Some(sock) = &c.socket {
                let _ = sock.shutdown(Shutdown::Both);
            }
        }
        ["list"] => {
            for p in &c.store.peers {
                println!("Trusted tablet {}", hexkey(p))
            }
        }
        ["revoke", key] => {
            let id = pin(key)?;
            c.store.trust(id, false)?;
            c.revision += 1;
            c.prompt.take();
            if let Some(s) = &c.socket {
                let _ = s.shutdown(Shutdown::Both);
            }
            println!("Trust revoked")
        }
        [cmd @ ("approve" | "reject"), tag] => {
            if c.prompt
                .as_ref()
                .is_some_and(|p| p.tag.eq_ignore_ascii_case(tag) && p.expires > Instant::now())
            {
                let p = c.prompt.take().unwrap();
                let _ = p.answer.try_send(*cmd == "approve");
            } else {
                println!("No matching live request")
            }
        }
        _ => println!(
            "Commands: approve <request>, reject <request>, list, revoke <tablet-key>, quit"
        ),
    };
    Ok(())
}
fn authorize(
    link: &mut Link,
    c: &Shared,
    attempts: &mut VecDeque<Instant>,
) -> Result<(bool, bool, bool)> {
    let mut b = [0; 256];
    let mut n = link.receive(&mut b, Instant::now() + Duration::from_secs(5))?;
    let dashboard = b[..n] == [FOCUS_HELLO, 3];
    let modern = dashboard || b[..n] == [FOCUS_HELLO, 2];
    let extended = modern || b[..n] == [FOCUS_HELLO];
    if extended {
        link.send(&b[..n])?;
        n = link.receive(&mut b, Instant::now() + Duration::from_secs(5))?;
    }
    match &b[..n] {
        [CONNECT] => {
            if !c.lock().unwrap().store.peers.contains(&link.peer) {
                link.send(&[UNTRUSTED])?;
                return Err(err("Unpaired tablet"));
            }
        }
        [PAIR] => {
            let now = Instant::now();
            while attempts
                .front()
                .is_some_and(|t| now.duration_since(*t) >= Duration::from_secs(60))
            {
                attempts.pop_front();
            }
            if attempts.len() >= 3 {
                link.send(&[DENIED])?;
                return Err(err("Pairing attempt limit reached"));
            }
            attempts.push_back(now);
            let until = now + TTL;
            let revision = c.lock().unwrap().revision;
            let tag = link.verification[..8].to_owned();
            let (tx, rx) = mpsc::sync_channel(1);
            c.lock().unwrap().prompt = Some(Prompt {
                tag: tag.clone(),
                verification: link.verification.clone(),
                expires: until,
                answer: tx,
            });
            link.send(&[VERIFY])?;
            let (mut desktop, mut tablet) = (false, false);
            while !(desktop && tablet) {
                if Instant::now() >= until {
                    return Err(err("Pairing expired"));
                }
                if !desktop {
                    match rx.try_recv() {
                        Ok(true) => desktop = true,
                        Ok(false) | Err(mpsc::TryRecvError::Disconnected) => {
                            link.send(&[DENIED])?;
                            return Err(err("Pairing rejected"));
                        }
                        Err(mpsc::TryRecvError::Empty) => {}
                    }
                }
                if link.readable(Duration::from_millis(75))? {
                    let n = link.receive(&mut b, until)?;
                    if tablet || &b[..n] != [CONFIRM] {
                        return Err(err("Expected a single tablet confirmation"));
                    }
                    tablet = true;
                }
            }
            let mut state = c.lock().unwrap();
            if state.stop || state.revision != revision || Instant::now() >= until {
                return Err(err("Pairing cancelled"));
            }
            state.store.trust(link.peer, true)?;
        }
        _ => return Err(err("Expected pairing or authenticated reconnect")),
    }
    Ok((extended, modern, dashboard))
}
fn session<S: mouse::Sink>(
    stream: TcpStream,
    c: &Shared,
    name: &str,
    attempts: &mut VecDeque<Instant>,
    sink: S,
) -> Result<()> {
    let key = c.lock().unwrap().store.key;
    let mut link = Link::open(stream, &key, false)?;
    let (extended, modern, dashboard) = authorize(&mut link, c, attempts)?;
    let mut audio = modern.then(audio::Audio::new);
    let mut dashboard = dashboard.then(dashboard::Dashboard::new);
    let mut watcher = companion::FocusWatch::default();
    c.lock().unwrap().ui.connected = Some((link.peer, "Android tablet".into()));
    let mut welcome = vec![READY];
    welcome.extend(name.as_bytes());
    link.send(&welcome)?;
    println!("Connected to tablet {}", hexkey(&link.peer));
    let mut buttons = HeldInput::new(sink);
    let mut buf = [0; 256];
    let mut received = 0u64;
    let mut processing = 0u128;
    let result = (|| -> Result<()> {
        loop {
            let n = link.receive(&mut buf, Instant::now() + Duration::from_secs(5))?;
            let enhanced = extended && n >= 3 && n <= 66 && buf[0] == FOCUS_PING;
            if &buf[..n] == [PING] || enhanced {
                let mut ack = [0; 22];
                ack[0] = PONG;
                ack[1..9].copy_from_slice(&received.to_be_bytes());
                ack[9..17]
                    .copy_from_slice(&(processing.min(u64::MAX as u128) as u64).to_be_bytes());
                if enhanced {
                    if buf[1] > 1
                        || !buf[2..n]
                            .iter()
                            .all(|b| b.is_ascii_alphanumeric() || b" -_".contains(b))
                    {
                        return Err(err("Invalid focus request"));
                    }
                    let mut g = c.lock().unwrap();
                    if g.stop || !g.store.peers.contains(&link.peer) {
                        return Err(err("Authorization withdrawn"));
                    }
                    if let Some((_, label)) = &mut g.ui.connected {
                        let model = std::str::from_utf8(&buf[2..n])
                            .map_err(|_| err("Invalid device label"))?;
                        if label != model {
                            label.clear();
                            label.push_str(model);
                        }
                    }
                    let enable = g.ui.automatic && buf[1] == 1;
                    drop(g);
                    let stamp = watcher.sample(enable);
                    ack[17] = (stamp & 3) as u8;
                    ack[18..22].copy_from_slice(&(stamp >> 2).to_be_bytes());
                }
                if let Some(d) = &mut dashboard {
                    d.publish(&mut link)?;
                }
                if let Some(audio) = &mut audio {
                    audio.publish(&mut link)?;
                }
                link.send(&ack[..if enhanced { 22 } else { 17 }])?;
                continue;
            }
            let start = Instant::now();
            let m = RemoteInput::decode(&buf[..n], received + 1)?;
            {
                let state = c.lock().unwrap();
                if state.stop || !state.store.peers.contains(&link.peer) {
                    return Err(err("Authorization withdrawn"));
                }
                match m {
                    RemoteInput::Launch(id) => dashboard
                        .as_ref()
                        .ok_or_else(|| err("Dashboard unavailable"))?
                        .launch(id),
                    RemoteInput::Audio(command) if modern => {
                        audio.as_mut().unwrap().command(command)
                    }
                    RemoteInput::Audio(_) | RemoteInput::Preedit(_) if !modern => {
                        return Err(err("Extension not negotiated"))
                    }
                    _ => buttons.apply(m)?,
                }
            }
            if matches!(m, RemoteInput::Button(1, false)) {
                watcher.after_click();
            }
            received += 1;
            processing += start.elapsed().as_nanos();
        }
    })();
    drop(buttons);
    println!("Disconnected. Received {received} events, processing {processing} ns total.");
    result
}
fn listen(c: Shared) -> Result<()> {
    let mut args = std::env::args().skip(1);
    let ip: Ipv4Addr = match (args.next(), args.next(), args.next()) {
        (None, None, None) => {
            let s = UdpSocket::bind("0.0.0.0:0")?;
            s.connect("239.255.255.250:9")?;
            match s.local_addr()?.ip() {
                std::net::IpAddr::V4(i) => i,
                _ => return Err(err("Select --bind <LAN IPv4>")),
            }
        }
        (Some(flag), Some(addr), None) if flag == "--bind" => addr.parse().map_err(err)?,
        _ => return Err(err("Usage: input-control-receiver [--bind <LAN IPv4>]")),
    };
    if !ip.is_private() && !ip.is_link_local() {
        return Err(err("Choose a private LAN IPv4 address using --bind"));
    }
    let mask = if_addrs::get_if_addrs()?
        .into_iter()
        .find_map(|i| match i.addr {
            if_addrs::IfAddr::V4(a) if a.ip == ip => Some(u32::from(a.netmask)),
            _ => None,
        })
        .ok_or_else(|| err("Selected interface unavailable"))?;
    let name = std::env::var("COMPUTERNAME").unwrap_or_else(|_| "Windows-PC".into());
    let name: String = name
        .chars()
        .filter(|c| c.is_ascii_alphanumeric() || *c == '-')
        .take(63)
        .collect();
    if name.is_empty() {
        return Err(err("Invalid PC name"));
    }
    let listener = TcpListener::bind((ip, PORT))?;
    listener.set_nonblocking(true)?;
    let service = ServiceInfo::new(
        SERVICE,
        &name,
        &format!("{name}.local."),
        std::net::IpAddr::V4(ip),
        PORT,
        None,
    )
    .map_err(err)?;
    let full = service.get_fullname().to_string();
    let mdns = ServiceDaemon::new().map_err(err)?;
    if let Err(error) = mdns.register(service) {
        let _ = mdns
            .shutdown()
            .map(|ack| ack.recv_timeout(Duration::from_secs(1)));
        return Err(err(error));
    }
    c.lock().unwrap().ui.listener = format!("Listening on {ip}:{PORT}");
    let busy = Arc::new(AtomicBool::new(false));
    let attempts = Arc::new(Mutex::new(VecDeque::new()));
    let mut worker: Option<thread::JoinHandle<()>> = None;
    while !c.lock().unwrap().stop {
        match listener.accept() {
            Ok((stream, addr)) => {
                let std::net::IpAddr::V4(peer) = addr.ip() else {
                    continue;
                };
                if u32::from(peer) & mask != u32::from(ip) & mask
                    || busy.swap(true, Ordering::AcqRel)
                {
                    continue;
                }
                let mut guard = c.lock().unwrap();
                if guard.stop {
                    break;
                }
                match stream.try_clone() {
                    Ok(control) => guard.socket = Some(control),
                    Err(error) => {
                        lifecycle::log(&format!(
                            "Cannot retain session socket for shutdown: {error}"
                        ));
                        busy.store(false, Ordering::Release);
                        continue;
                    }
                }
                drop(guard);
                let (shared, busy, tries, name) =
                    (c.clone(), busy.clone(), attempts.clone(), name.clone());
                // busy is cleared only after session cleanup. Reap the preceding worker.
                if let Some(previous) = worker.take() {
                    let _ = previous.join();
                }
                worker = Some(thread::spawn(move || {
                    if let Err(e) = session(
                        stream,
                        &shared,
                        &name,
                        &mut tries.lock().unwrap(),
                        Desktop::default(),
                    ) {
                        eprintln!("Session ended: {e}")
                    }
                    let mut g = shared.lock().unwrap();
                    g.ui.connected = None;
                    g.prompt = None;
                    g.socket = None;
                    busy.store(false, Ordering::Release);
                }));
            }
            Err(e) if e.kind() == io::ErrorKind::WouldBlock => {
                thread::sleep(Duration::from_millis(120))
            }
            Err(e) => {
                lifecycle::log(&format!("Listener accept failed: {e}"));
                let mut state = c.lock().unwrap();
                state.ui.listener = format!("Stopped · {e}");
                if let Some(socket) = &state.socket {
                    let _ = socket.shutdown(Shutdown::Both);
                }
                break;
            }
        }
    }
    drop(listener);
    if let Some(j) = worker {
        let _ = j.join();
    }
    let _ = mdns
        .unregister(&full)
        .map(|r| r.recv_timeout(Duration::from_secs(1)));
    let _ = mdns
        .shutdown()
        .map(|r| r.recv_timeout(Duration::from_secs(1)));
    Ok(())
}

fn main() {
    let _instance = match lifecycle::Instance::acquire() {
        Ok(Some(guard)) => guard,
        Ok(None) => return,
        Err(error) => {
            lifecycle::log(&format!("Cannot acquire instance mutex: {error}"));
            companion::error(&format!("Cannot start the companion: {error}"));
            return;
        }
    };
    if let Err(error) = companion::run() {
        lifecycle::log(&format!("Startup or shutdown failed: {error}"));
        // Keep ownership while a startup-error dialog is still on the desktop.
        companion::error(&error.to_string());
    }
    lifecycle::log("shutdown: receiver resources released");
}

#[cfg(test)]
mod tests;
