use crate::lifecycle::file_error;
use input_wifi::{err, secret, Result};
use std::{
    collections::BTreeSet,
    fs::{self, File, OpenOptions},
    io::Write,
    os::windows::fs::OpenOptionsExt,
    path::PathBuf,
    ptr,
};
use windows_sys::Win32::{
    Foundation::LocalFree, Security::Cryptography::*, Storage::FileSystem::*,
};

fn protect(bytes: &[u8], encrypt: bool) -> Result<Vec<u8>> {
    let input = CRYPT_INTEGER_BLOB {
        cbData: bytes.len() as u32,
        pbData: bytes.as_ptr() as _,
    };
    let mut output = unsafe { std::mem::zeroed() };
    // DPAPI allocates output with LocalAlloc. User scope binds it to this Windows account.
    let ok = unsafe {
        if encrypt {
            CryptProtectData(
                &input,
                ptr::null(),
                ptr::null(),
                ptr::null_mut(),
                ptr::null(),
                CRYPTPROTECT_UI_FORBIDDEN,
                &mut output,
            )
        } else {
            CryptUnprotectData(
                &input,
                ptr::null_mut(),
                ptr::null(),
                ptr::null_mut(),
                ptr::null(),
                CRYPTPROTECT_UI_FORBIDDEN,
                &mut output,
            )
        }
    };
    if ok == 0 {
        return Err(std::io::Error::last_os_error());
    }
    let result =
        unsafe { std::slice::from_raw_parts(output.pbData, output.cbData as usize).to_vec() };
    unsafe { LocalFree(output.pbData as _) };
    Ok(result)
}
pub struct Store {
    pub key: [u8; 32],
    pub peers: BTreeSet<[u8; 32]>,
    path: PathBuf,
    _lock: File,
}
impl Store {
    pub fn open() -> Result<Self> {
        let dir = PathBuf::from(
            std::env::var_os("LOCALAPPDATA").ok_or_else(|| err("LOCALAPPDATA missing"))?,
        )
        .join("Input Control Receiver");
        Self::open_in(dir)
    }
    pub(crate) fn open_in(dir: PathBuf) -> Result<Self> {
        fs::create_dir_all(&dir).map_err(|e| file_error("Create data directory", &dir, e))?;
        let lock_path = dir.join("receiver.lock");
        let lock = OpenOptions::new()
            .create(true)
            .write(true)
            .truncate(false)
            .share_mode(0)
            .open(&lock_path)
            .map_err(|e| file_error("Open exclusive trust-store guard", &lock_path, e))?;
        let path = dir.join("trust.dpapi");
        let mut s = Self {
            key: [0; 32],
            peers: BTreeSet::new(),
            path,
            _lock: lock,
        };
        match fs::read(&s.path) {
            Ok(enc) => {
                let b = protect(&enc, false)?;
                if b.len() < 32 || b.len() % 32 != 0 || b.len() > 32 * 65 {
                    return Err(err("Invalid trust store"));
                }
                s.key.copy_from_slice(&b[..32]);
                for pin in b[32..].chunks_exact(32) {
                    s.peers.insert(pin.try_into().unwrap());
                }
            }
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
                s.key = secret()?;
                s.save(&s.peers)?
            }
            Err(e) => return Err(file_error("Read trust store", &s.path, e)),
        }
        Ok(s)
    }
    fn save(&self, peers: &BTreeSet<[u8; 32]>) -> Result<()> {
        if peers.len() > 64 {
            return Err(err("Trust list full"));
        }
        let mut data = self.key.to_vec();
        for p in peers {
            data.extend_from_slice(p)
        }
        let tmp = self.path.with_extension("tmp");
        let mut f = File::create(&tmp).map_err(|e| file_error("Create staging file", &tmp, e))?;
        f.write_all(&protect(&data, true)?)
            .map_err(|e| file_error("Write staging file", &tmp, e))?;
        f.sync_all()
            .map_err(|e| file_error("Flush staging file", &tmp, e))?;
        drop(f);
        use std::os::windows::ffi::OsStrExt;
        let w = |p: &std::path::Path| {
            p.as_os_str()
                .encode_wide()
                .chain(Some(0))
                .collect::<Vec<_>>()
        };
        if unsafe {
            MoveFileExW(
                w(&tmp).as_ptr(),
                w(&self.path).as_ptr(),
                MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH,
            )
        } == 0
        {
            return Err(file_error(
                "Replace trust store",
                &self.path,
                std::io::Error::last_os_error(),
            ));
        }
        Ok(())
    }
    pub fn trust(&mut self, key: [u8; 32], add: bool) -> Result<()> {
        let mut next = self.peers.clone();
        if add {
            next.insert(key);
        } else {
            next.remove(&key);
        }
        self.save(&next)?;
        self.peers = next;
        Ok(())
    }
}
