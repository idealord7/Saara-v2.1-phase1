/**
 * Port and extension of Saara's on-device privacy interceptor with dynamic automation bypass.
 */
import { AIEmotion } from '../types';

const SENSITIVE_PHRASES = [
  'my contact', 'my contacts', 'my phone', 'my address', 'call history',
  'call log', 'sms history', 'my messages', 'message from', 'my email',
  'my whatsapp', 'my saved learning', 'my knowledge', 'my sheet',
  'my spreadsheet', 'my call', 'dial ', 'send sms', 'remind me', 'schedule ',
  'আমার নম্বর', 'আমার কন্টাক্ট', 'আমার যোগাযোগ', 'আমার মেসেজ', 'আমার কল',
  'আমার ঠিকানা', 'ব্যাংক অ্যাকাউন্ট', 'मेरा नंबर', 'मेरे संपर्क', 'मेरे मैसेज',
  'मेरी कॉल हिस्ट्री', 'मेरा पता', 'बैंक खाता'
];

const SENSITIVE_KEYWORDS = [
  'bank account', 'account number', 'credit card', 'cvv', 'password', 'social security'
];

// Indicators of intentional device automation tasks that require cloud vision & multi-step execution
const AUTOMATION_INTENTS = [
  'go to chrome', 'open chrome', 'search', 'browse', 'extract data', 'gather data',
  'share data', 'share with', 'send happy birthday', 'send due reminder',
  'take screenshot', 'attach document', 'tap', 'click', 'scroll', 'automate',
  'run task', 'macro', 'find on sheet', 'lookup price', 'read screen', 'whatsapp'
];

const PHONE_REGEX = /\b[+0-9][0-9 ()-]{7,}\b/;
const EMAIL_REGEX = /[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}/i;

export interface PrivacyCheckResult {
  isSensitive: boolean;
  bypassedForAutomation?: boolean;
  isAutomationTask?: boolean;
  reason?: string;
  matchedPattern?: string;
}

export function evaluatePrivacyGuard(
  text: string,
  privacyModeEnabled: boolean = true,
  autoPrivacyForAutomation: boolean = true
): PrivacyCheckResult {
  if (!privacyModeEnabled) {
    return { isSensitive: false };
  }

  const query = text.toLowerCase();

  // Check if this is an intentional long automation task
  const isAutomation = AUTOMATION_INTENTS.some(intent => query.includes(intent));

  if (isAutomation && autoPrivacyForAutomation) {
    return {
      isSensitive: false,
      bypassedForAutomation: true,
      isAutomationTask: true,
      reason: 'Dynamic Privacy Mode: Temporarily lifted for high-performance device automation task'
    };
  }

  // Check sensitive keywords (strict passwords and bank accounts are guarded unless explicit)
  for (const kw of SENSITIVE_KEYWORDS) {
    if (query.includes(kw)) {
      return {
        isSensitive: true,
        reason: 'Sensitive financial or security identifier detected',
        matchedPattern: kw
      };
    }
  }

  // If this is an automation task (like sending a birthday message to a contact), allow it
  if (isAutomation) {
    return { isSensitive: false, isAutomationTask: true };
  }

  // Check sensitive phrases
  for (const phrase of SENSITIVE_PHRASES) {
    if (query.includes(phrase)) {
      return {
        isSensitive: true,
        reason: 'Sensitive personal device data phrase detected',
        matchedPattern: phrase
      };
    }
  }

  // Check phone format
  if (PHONE_REGEX.test(text)) {
    return {
      isSensitive: true,
      reason: 'Phone number pattern detected',
      matchedPattern: 'Phone number'
    };
  }

  // Check email format
  if (EMAIL_REGEX.test(text)) {
    return {
      isSensitive: true,
      reason: 'Email address pattern detected',
      matchedPattern: 'Email address'
    };
  }

  return { isSensitive: false };
}

export function getEmotionEmoji(emotion: AIEmotion = 'neutral'): string {
  switch (emotion) {
    case 'thinking':
      return '🤔 Thinking';
    case 'happy':
      return '😊 Happy';
    case 'confused':
      return '😕 Confused';
    case 'sad':
      return '😭 Sad';
    case 'focused':
      return '⚡ Focused / Automating';
    case 'protective':
      return '🛡️ Privacy Guard';
    default:
      return '✨ Saara';
  }
}
