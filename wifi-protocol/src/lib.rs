//! Shared bounded framing over the standard Noise XX handshake and transport.
use snow::{Builder, TransportState};
use std::{
    io::{self, Read, Write},
    net::TcpStream,
    time::{Duration, Instant},
};
pub const PORT: u16 = 47831;
pub const SERVICE: &str = "_inputcontrol._tcp.local.";
pub const PAIR: u8 = 0x10;
pub const CONNECT: u8 = 0x11;
pub const VERIFY: u8 = 0x12;
pub const CONFIRM: u8 = 0x13;
pub const READY: u8 = 0x14;
pub const DENIED: u8 = 0x15;
pub const UNTRUSTED: u8 = 0x16;
pub const PING: u8 = 0x17;
pub const PONG: u8 = 0x18;
// Optional negotiation before the existing enrollment/reconnect exchange.
pub const FOCUS_HELLO: u8 = 0x19;
pub const DASHBOARD: u8 = 0x23;
pub const AUDIO_STATE: u8 = 0x21;
pub const AUDIO_OUTPUT: u8 = 0x22;
pub const FOCUS_PING: u8 = 0x1a;
#[repr(u8)]
#[derive(Clone, Copy, PartialEq, Debug)]
pub enum Focus {
    Unknown = 0,
    Editable = 1,
    Lost = 2,
}
impl TryFrom<u8> for Focus {
    type Error = io::Error;
    fn try_from(n: u8) -> Result<Self> {
        match n {
            0 => Ok(Self::Unknown),
            1 => Ok(Self::Editable),
            2 => Ok(Self::Lost),
            _ => Err(err("Invalid focus state")),
        }
    }
}
pub const TTL: Duration = Duration::from_secs(60);
const MAX: usize = 256;
pub type Result<T> = io::Result<T>;
pub fn err(s: impl ToString) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, s.to_string())
}
fn builder() -> Builder<'static> {
    Builder::new(
        "Noise_XX_25519_ChaChaPoly_SHA256"
            .parse()
            .expect("constant Noise suite"),
    )
    .prologue(b"Input Control local mouse v1")
    .expect("one fixed prologue")
}
pub fn secret() -> Result<[u8; 32]> {
    builder()
        .generate_keypair()
        .map_err(err)?
        .private
        .try_into()
        .map_err(|_| err("invalid Noise key"))
}
pub fn hexkey(bytes: &[u8]) -> String {
    hex::encode_upper(bytes)
}
pub fn pin(text: &str) -> Result<[u8; 32]> {
    let mut out = [0; 32];
    hex::decode_to_slice(text, &mut out).map_err(err)?;
    Ok(out)
}
fn exact(stream: &mut TcpStream, mut dst: &mut [u8], until: Instant) -> Result<()> {
    while !dst.is_empty() {
        let left = until
            .checked_duration_since(Instant::now())
            .ok_or_else(|| err("Connection timed out"))?;
        stream.set_read_timeout(Some(left.max(Duration::from_millis(1))))?;
        let n = stream.read(dst).map_err(|e| match e.kind() {
            io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut => io::Error::new(
                io::ErrorKind::TimedOut,
                "Connection timed out waiting for peer",
            ),
            _ => e,
        })?;
        if n == 0 {
            return Err(err("Receiver or tablet disconnected"));
        }
        dst = &mut dst[n..];
    }
    Ok(())
}
fn read_frame(s: &mut TcpStream, dst: &mut [u8; MAX], until: Instant) -> Result<usize> {
    let mut hdr = [0; 2];
    exact(s, &mut hdr, until)?;
    let n = u16::from_be_bytes(hdr) as usize;
    if !(1..=MAX).contains(&n) {
        return Err(err("Invalid frame size"));
    }
    exact(s, &mut dst[..n], until)?;
    Ok(n)
}
fn frame(s: &mut TcpStream, b: &[u8]) -> Result<()> {
    let mut packet = [0; MAX + 2];
    if b.len() > MAX {
        return Err(err("Oversized frame"));
    }
    packet[..2].copy_from_slice(&(b.len() as u16).to_be_bytes());
    packet[2..2 + b.len()].copy_from_slice(b);
    s.write_all(&packet[..2 + b.len()])
}
pub struct Link {
    socket: TcpStream,
    cipher: TransportState,
    pub peer: [u8; 32],
    pub verification: String,
}
fn validate_public(bytes: &[u8; 32]) -> Result<()> {
    // Reject non-contributory X25519 public keys (RFC 7748 §6.1).
    if curve25519_dalek::montgomery::MontgomeryPoint(*bytes)
        .mul_clamped([42; 32])
        .to_bytes()
        == [0; 32]
    {
        return Err(err("Invalid X25519 public key"));
    }
    Ok(())
}
impl Link {
    pub fn open(mut socket: TcpStream, key: &[u8; 32], client: bool) -> Result<Self> {
        // Winsock can inherit FIONBIO from our polling listener. Each session owns a
        // blocking worker with per-frame deadlines.
        socket.set_nonblocking(false)?;
        socket.set_nodelay(true)?;
        socket.set_write_timeout(Some(Duration::from_secs(3)))?;
        let b = builder().local_private_key(key).map_err(err)?;
        let mut hs = if client {
            b.build_initiator()
        } else {
            b.build_responder()
        }
        .map_err(err)?;
        let until = Instant::now() + Duration::from_secs(6);
        let mut buf = [0; MAX];
        let mut plain = [0; MAX];
        let mut first_read = true;
        while !hs.is_handshake_finished() {
            if hs.is_my_turn() {
                let n = hs.write_message(&[], &mut buf).map_err(err)?;
                frame(&mut socket, &buf[..n])?
            } else {
                let n = read_frame(&mut socket, &mut buf, until)?;
                if first_read {
                    let ephemeral = buf[..n]
                        .get(..32)
                        .ok_or_else(|| err("Truncated Noise handshake"))?;
                    validate_public(ephemeral.try_into().unwrap())?;
                    first_read = false;
                }
                if hs.read_message(&buf[..n], &mut plain).map_err(err)? != 0 {
                    return Err(err("Unexpected handshake payload"));
                }
            }
        }
        let peer = hs
            .get_remote_static()
            .and_then(|p| p.try_into().ok())
            .ok_or_else(|| err("Missing peer identity"))?;
        let verification = hexkey(&hs.get_handshake_hash()[..16]);
        validate_public(&peer)?;
        Ok(Self {
            socket,
            cipher: hs.into_transport_mode().map_err(err)?,
            peer,
            verification,
        })
    }
    pub fn send(&mut self, b: &[u8]) -> Result<()> {
        if b.len() > MAX - 16 {
            return Err(err("Oversized message"));
        }
        let mut out = [0; MAX];
        let n = self.cipher.write_message(b, &mut out).map_err(err)?;
        frame(&mut self.socket, &out[..n])
    }
    pub fn readable(&self, timeout: Duration) -> Result<bool> {
        self.socket.set_read_timeout(Some(timeout))?;
        match self.socket.peek(&mut [0]) {
            Ok(0) => Err(err("Peer disconnected")),
            Ok(_) => Ok(true),
            Err(e)
                if matches!(
                    e.kind(),
                    io::ErrorKind::TimedOut | io::ErrorKind::WouldBlock
                ) =>
            {
                Ok(false)
            }
            Err(e) => Err(e),
        }
    }
    pub fn receive(&mut self, out: &mut [u8; 256], until: Instant) -> Result<usize> {
        let mut buf = [0; MAX];
        let n = read_frame(&mut self.socket, &mut buf, until)?;
        self.cipher.read_message(&buf[..n], out).map_err(err)
    }
}
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum RemoteInput {
    Move(i32, i32),
    Button(u8, bool),
    Wheel(i32),
    Release,
    Key(u8, bool),
    Text(char),
    Preedit(Option<char>),
    Audio(u8),
    Launch(i32),
}
pub fn supported_key(id: i32) -> bool {
    matches!(id,4..=49|51..=56|58..=69|76|79..=82|84..=99|224..=227)
}
impl RemoteInput {
    pub fn encode(self, seq: u64) -> [u8; 17] {
        let (op, x, y) = match self {
            Self::Move(x, y) => (1, x, y),
            Self::Button(b, p) => (2, i32::from(b), i32::from(p)),
            Self::Wheel(y) => (3, 0, y),
            Self::Release => (4, 0, 0),
            Self::Key(k, p) => (5, i32::from(k), i32::from(p)),
            Self::Preedit(c) => (7, c.map_or(0, |v| v as i32), 0),
            Self::Launch(id) => (9, id, 0),
            Self::Audio(a) => (8, i32::from(a), 0),
            Self::Text(c) => (6, c as i32, 0),
        };
        let mut b = [0; 17];
        b[0] = op;
        b[1..9].copy_from_slice(&seq.to_be_bytes());
        b[9..13].copy_from_slice(&x.to_be_bytes());
        b[13..].copy_from_slice(&y.to_be_bytes());
        b
    }
    pub fn decode(b: &[u8], expected: u64) -> Result<Self> {
        if b.len() != 17 || b[1..9] != expected.to_be_bytes() {
            return Err(err("Invalid sequence or length"));
        }
        let x = i32::from_be_bytes(b[9..13].try_into().unwrap());
        let y = i32::from_be_bytes(b[13..].try_into().unwrap());
        if matches!(b[0], 1 | 3) && (x.unsigned_abs() > 32767 || y.unsigned_abs() > 32767) {
            return Err(err("Delta out of range"));
        }
        match (b[0], x, y) {
            (1, x, y) => Ok(Self::Move(x, y)),
            (2, b @ 1..=2, p @ 0..=1) => Ok(Self::Button(b as u8, p == 1)),
            (3, 0, y) => Ok(Self::Wheel(y)),
            (4, 0, 0) => Ok(Self::Release),
            (5, k, p @ 0..=1) if supported_key(k) => Ok(Self::Key(k as u8, p == 1)),
            (7, 0, 0) => Ok(Self::Preedit(None)),
            (7, c @ (0x3131..=0x3163 | 0xac00..=0xd7a3), 0) => {
                Ok(Self::Preedit(char::from_u32(c as u32)))
            }
            (8, a @ (0..=7 | 100..=200), 0) => Ok(Self::Audio(a as u8)),
            (9, id, 0) if id > 0 => Ok(Self::Launch(id)),
            (6, c, 0) => char::from_u32(c as u32)
                .filter(|c| !c.is_control())
                .map(Self::Text)
                .ok_or_else(|| err("Invalid text scalar")),
            _ => Err(err("Unsupported input message")),
        }
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn frames() {
        for m in [
            RemoteInput::Move(-7, 8),
            RemoteInput::Button(2, true),
            RemoteInput::Button(1, false),
            RemoteInput::Wheel(-120),
            RemoteInput::Release,
            RemoteInput::Preedit(Some('요')),
            RemoteInput::Preedit(None),
            RemoteInput::Audio(200),
            RemoteInput::Launch(42),
        ] {
            let b = m.encode(8);
            assert_eq!(RemoteInput::decode(&b, 8).unwrap(), m);
            assert!(RemoteInput::decode(&b, 9).is_err());
            assert!(RemoteInput::decode(&b[..10], 8).is_err())
        }
        for id in [-1, 0] {
            assert!(RemoteInput::decode(&RemoteInput::Launch(id).encode(1), 1).is_err());
        }
        assert!(RemoteInput::decode(&RemoteInput::Audio(99).encode(1), 1).is_err());
        assert!(RemoteInput::decode(&RemoteInput::Preedit(Some('😀')).encode(1), 1).is_err());
        assert!(RemoteInput::decode(&RemoteInput::Move(i32::MIN, 0).encode(1), 1).is_err());
        assert!(RemoteInput::decode(&RemoteInput::Button(3, true).encode(1), 1).is_err())
    }
}
