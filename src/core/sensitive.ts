/**
 * Sensitive Content Guard (Port of Sensitive.kt)
 * Prevents saving clipboard snippets that resemble passwords, OTPs, verification codes,
 * 4-8 digit PINs, or Luhn-valid payment card numbers (13-19 digits).
 */
const SENSITIVE_KEYWORDS =
  /(password|passcode|otp|verification code|one-time|رمز التحقق|كود التحقق|رمز التفعيل|كلمة المرور|كلمة السر|الرقم السري)/i;

export function luhnCheck(digits: string): boolean {
  let sum = 0;
  let alt = false;
  for (let i = digits.length - 1; i >= 0; i--) {
    const code = digits.charCodeAt(i) - 48;
    if (code < 0 || code > 9) return false;
    let n = code;
    if (alt) {
      n *= 2;
      if (n > 9) n -= 9;
    }
    sum += n;
    alt = !alt;
  }
  return sum % 10 === 0;
}

export function looksSensitive(text: string): boolean {
  const trimmed = text.trim();
  if (SENSITIVE_KEYWORDS.test(trimmed)) {
    return true;
  }
  if (trimmed.length > 0 && /^[\d\s-]+$/.test(trimmed)) {
    const digits = trimmed.replace(/\D/g, '');
    if (digits.length >= 4 && digits.length <= 8) {
      return true;
    }
    if (digits.length >= 13 && digits.length <= 19 && luhnCheck(digits)) {
      return true;
    }
  }
  return false;
}
