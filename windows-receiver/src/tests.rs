use super::*;
use crate::mouse::Sink;
use std::path::PathBuf;

#[derive(Default, Clone)]
struct Recorder(Arc<Mutex<Vec<RemoteInput>>>);
impl Sink for Recorder {
    fn inject(&mut self, m: RemoteInput) -> Result<()> {
        self.0.lock().unwrap().push(m);
        Ok(())
    }
}
struct Fixture {
    state: Shared,
    dir: PathBuf,
}
impl Fixture {
    fn new() -> Self {
        let dir = std::env::temp_dir().join(format!("ic-test-{}", hexkey(&secret().unwrap())));
        let store = Store::open_in(dir.clone()).unwrap();
        Self {
            state: Arc::new(Mutex::new(Console {
                store,
                prompt: None,
                socket: None,
                stop: false,
                revision: 0,
                ui: Default::default(),
            })),
            dir,
        }
    }
    fn dial(
        &self,
        key: &[u8; 32],
        prior: usize,
    ) -> (Link, Recorder, thread::JoinHandle<Result<()>>) {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        let state = self.state.clone();
        let record = Recorder::default();
        let sink = record.clone();
        let server = thread::spawn(move || {
            let (s, _) = listener.accept()?;
            s.set_nonblocking(true)?;
            let mut attempts = VecDeque::from(vec![Instant::now(); prior]);
            session(s, &state, "Test-PC", &mut attempts, sink)
        });
        (
            Link::open(TcpStream::connect(address).unwrap(), key, true).unwrap(),
            record,
            server,
        )
    }
    fn end(self) {
        drop(self.state);
        for file in ["receiver.lock", "trust.dpapi"] {
            std::fs::remove_file(self.dir.join(file)).unwrap()
        }
        std::fs::remove_dir(self.dir).unwrap()
    }
}
fn read(link: &mut Link) -> Vec<u8> {
    let mut b = [0; 256];
    let n = link
        .receive(&mut b, Instant::now() + Duration::from_secs(3))
        .unwrap();
    b[..n].to_vec()
}
#[test]
fn unpaired_or_premature_input_never_reaches_mouse() {
    let f = Fixture::new();
    for opening in [
        vec![CONNECT],
        vec![FOCUS_PING, 1, b'T'],
        RemoteInput::Audio(100).encode(1).to_vec(),
        RemoteInput::Launch(42).encode(1).to_vec(),
        RemoteInput::Preedit(Some('한')).encode(1).to_vec(),
        RemoteInput::Button(1, true).encode(1).to_vec(),
    ] {
        let (mut link, record, job) = f.dial(&secret().unwrap(), 0);
        link.send(&opening).unwrap();
        if opening == [CONNECT] {
            assert_eq!(read(&mut link), [UNTRUSTED]);
        }
        assert!(job.join().unwrap().is_err());
        assert!(record.0.lock().unwrap().is_empty());
    }
    f.end()
}
#[test]
fn explicit_rejection_and_attempt_limit() {
    let f = Fixture::new();
    for prior in [0, 3] {
        let (mut link, record, job) = f.dial(&secret().unwrap(), prior);
        link.send(&[PAIR]).unwrap();
        if prior == 0 {
            assert_eq!(read(&mut link), [VERIFY]);
            command(&f.state, &format!("reject {}", &link.verification[..8])).unwrap();
        }
        assert_eq!(read(&mut link), [DENIED]);
        assert!(job.join().unwrap().is_err());
        assert!(record.0.lock().unwrap().is_empty());
        assert!(f.state.lock().unwrap().store.peers.is_empty());
    }
    f.end()
}
#[test]
fn both_confirmations_then_reconnect_sequence_cleanup_and_revoke() {
    let f = Fixture::new();
    let client = secret().unwrap();
    let (mut link, record, job) = f.dial(&client, 0);
    link.send(&[PAIR]).unwrap();
    assert_eq!(read(&mut link), [VERIFY]);
    let server_id = link.peer;
    let tag = link.verification[..8].to_owned();
    command(&f.state, &format!("approve {tag}")).unwrap();
    assert!(f.state.lock().unwrap().store.peers.is_empty());
    link.send(&[CONFIRM]).unwrap();
    assert_eq!(read(&mut link), [&[READY][..], b"Test-PC"].concat());
    let events = [
        RemoteInput::Move(8, -2),
        RemoteInput::Button(1, true),
        RemoteInput::Button(1, false),
        RemoteInput::Wheel(-120),
        RemoteInput::Button(2, true),
        RemoteInput::Text('한'),
        RemoteInput::Key(225, true),
        RemoteInput::Key(224, true),
        RemoteInput::Key(6, true),
        RemoteInput::Key(6, false),
    ];
    for (i, m) in events.iter().enumerate() {
        link.send(&m.encode(i as u64 + 1)).unwrap()
    }
    link.send(&[PING]).unwrap();
    let ack = read(&mut link);
    assert_eq!(ack[0], PONG);
    assert_eq!(
        u64::from_be_bytes(ack[1..9].try_into().unwrap()),
        events.len() as u64
    );
    // Repeated sequence terminates the stream and releases its held right button.
    link.send(&RemoteInput::Move(1, 0).encode(5)).unwrap();
    assert!(job.join().unwrap().is_err());
    assert_eq!(
        *record.0.lock().unwrap(),
        [
            events.as_slice(),
            &[
                RemoteInput::Key(224, false),
                RemoteInput::Key(225, false),
                RemoteInput::Button(2, false)
            ]
        ]
        .concat()
    );
    let (mut second, _, job) = f.dial(&client, 0);
    assert_eq!(second.peer, server_id);
    second.send(&[FOCUS_HELLO]).unwrap();
    assert_eq!(read(&mut second), [FOCUS_HELLO]);
    second.send(&[CONNECT]).unwrap();
    assert_eq!(read(&mut second)[0], READY);
    second.send(&[FOCUS_PING, 0, b'T']).unwrap();
    let response = read(&mut second);
    assert_eq!(response.len(), 22);
    assert_eq!(response[0], PONG);
    assert_eq!(&response[17..], &[0; 5]);
    second.send(&[FOCUS_PING, 2, b'T']).unwrap();
    assert!(job.join().unwrap().is_err());
    let id = *f.state.lock().unwrap().store.peers.first().unwrap();
    command(&f.state, &format!("revoke {}", hexkey(&id))).unwrap();
    let (mut third, _, job) = f.dial(&client, 0);
    third.send(&[CONNECT]).unwrap();
    assert_eq!(read(&mut third), [UNTRUSTED]);
    assert!(job.join().unwrap().is_err());
    f.end()
}

#[test]
fn trust_guard_reports_conflicting_path_and_reopens_after_release() {
    let f = Fixture::new();
    let path = f.dir.join("receiver.lock");
    let failure = Store::open_in(f.dir.clone())
        .err()
        .expect("second store must fail");
    assert!(failure.to_string().contains(&*path.display().to_string()));
    assert!(failure.to_string().contains("32"));
    let dir = f.dir.clone();
    drop(f.state);
    drop(Store::open_in(dir.clone()).unwrap());
    std::fs::remove_dir_all(dir).unwrap();
}
