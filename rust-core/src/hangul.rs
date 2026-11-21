//! Modern two-set Hangul. Only the active syllable is mutable.
const INITIAL: &str = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ";
const VOWEL: &str = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ";
const FINAL: &str = " ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ";
const JOINS: &[(char, char, char)] = &[
    ('ㅗ', 'ㅏ', 'ㅘ'),
    ('ㅗ', 'ㅐ', 'ㅙ'),
    ('ㅗ', 'ㅣ', 'ㅚ'),
    ('ㅜ', 'ㅓ', 'ㅝ'),
    ('ㅜ', 'ㅔ', 'ㅞ'),
    ('ㅜ', 'ㅣ', 'ㅟ'),
    ('ㅡ', 'ㅣ', 'ㅢ'),
    ('ㄱ', 'ㅅ', 'ㄳ'),
    ('ㄴ', 'ㅈ', 'ㄵ'),
    ('ㄴ', 'ㅎ', 'ㄶ'),
    ('ㄹ', 'ㄱ', 'ㄺ'),
    ('ㄹ', 'ㅁ', 'ㄻ'),
    ('ㄹ', 'ㅂ', 'ㄼ'),
    ('ㄹ', 'ㅅ', 'ㄽ'),
    ('ㄹ', 'ㅌ', 'ㄾ'),
    ('ㄹ', 'ㅍ', 'ㄿ'),
    ('ㄹ', 'ㅎ', 'ㅀ'),
    ('ㅂ', 'ㅅ', 'ㅄ'),
];
fn join(a: char, b: char) -> Option<char> {
    JOINS
        .iter()
        .find(|&&(x, y, _)| x == a && y == b)
        .map(|t| t.2)
}
fn split(c: char) -> Option<(char, char)> {
    JOINS.iter().find(|t| t.2 == c).map(|t| (t.0, t.1))
}
fn index(s: &str, c: char) -> u32 {
    s.chars().position(|x| x == c).unwrap() as u32
}

#[derive(Default)]
pub struct Composer {
    initial: Option<char>,
    vowel: Option<char>,
    tail: Option<char>,
}
impl Composer {
    pub fn current(&self) -> Option<char> {
        match (self.initial, self.vowel) {
            (Some(i), Some(v)) => char::from_u32(
                0xac00
                    + (index(INITIAL, i) * 21 + index(VOWEL, v)) * 28
                    + self.tail.map_or(0, |t| index(FINAL, t)),
            ),
            (i, v) => i.or(v),
        }
    }
    pub fn finish(&mut self) -> Option<char> {
        let c = self.current();
        *self = Self::default();
        c
    }
    pub fn backspace(&mut self) -> bool {
        if let Some(t) = self.tail {
            self.tail = split(t).map(|p| p.0)
        } else if let Some(v) = self.vowel {
            self.vowel = split(v).map(|p| p.0)
        } else {
            return self.initial.take().is_some();
        }
        true
    }
    pub fn push(&mut self, c: char) -> Option<char> {
        if VOWEL.contains(c) {
            if let Some(t) = self.tail.take() {
                let (keep, carry) = split(t).map_or((None, t), |(a, b)| (Some(a), b));
                self.tail = keep;
                let previous = self.finish();
                self.initial = Some(carry);
                self.vowel = Some(c);
                return previous;
            }
            if let Some(v) = self.vowel {
                if let Some(combo) = join(v, c) {
                    self.vowel = Some(combo);
                    return None;
                }
                let previous = self.finish();
                self.vowel = Some(c);
                return previous;
            }
            self.vowel = Some(c);
            None
        } else if INITIAL.contains(c) {
            if self.initial.is_some() && self.vowel.is_some() && FINAL.contains(c) {
                if self.tail.is_none() {
                    self.tail = Some(c);
                    return None;
                }
                if let Some(combo) = join(self.tail.unwrap(), c) {
                    self.tail = Some(combo);
                    return None;
                }
            }
            let previous = self.finish();
            self.initial = Some(c);
            previous
        } else {
            self.finish()
        }
    }
}
pub fn two_set(letter: char, shift: bool) -> Option<char> {
    let i = "qwertyuiopasdfghjklzxcvbnm".find(letter)?;
    let labels = if shift {
        "ㅃㅉㄸㄲㅆㅛㅕㅑㅒㅖㅁㄴㅇㄹㅎㅗㅓㅏㅣㅋㅌㅊㅍㅠㅜㅡ"
    } else {
        "ㅂㅈㄷㄱㅅㅛㅕㅑㅐㅔㅁㄴㅇㄹㅎㅗㅓㅏㅣㅋㅌㅊㅍㅠㅜㅡ"
    };
    labels.chars().nth(i)
}
