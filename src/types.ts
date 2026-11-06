export type MessageRole = 'you' | 'saara' | 'tool' | 'system';

export type AIEmotion = 'neutral' | 'thinking' | 'happy' | 'confused' | 'sad' | 'protective' | 'focused';

export interface Message {
  id: string;
  role: MessageRole;
  text: string;
  timestamp: string; // Full date and time e.g. "Nov 4, 2026 • 07:45 AM"
  emotion?: AIEmotion;
  toolCall?: {
    name: string;
    args: any;
    result?: string;
  };
}

export interface DeviceState {
  flashlight: boolean;
  volume: number; // 0 - 100
  wifi: boolean;
  bluetooth: boolean;
  muted: boolean;
  battery: number;
  charging: boolean;
  activeApp: string; // e.g. 'home', 'chrome', 'whatsapp', 'camera', 'gallery', 'notes', 'settings'
  activeTimer: {
    duration: number; // seconds
    remaining: number;
    description: string;
  } | null;
}

export interface ScheduledTask {
  id: string;
  description: string;
  timeOrDelay: string;
  createdAt: string;
  completed: boolean;
}

export type MacroTriggerType =
  | 'time'
  | 'date'
  | 'interval'
  | 'incoming_call'
  | 'message_receive'
  | 'app_launch'
  | 'location'
  | 'notification'
  | 'system_settings'
  | 'sensor'
  | 'screen_content'
  | 'photo_capture';

export interface MacroRule {
  id: string;
  name: string;
  description: string;
  triggerType: MacroTriggerType;
  triggerValue: string; // e.g., "5:00 PM", "Mom", "Battery < 20%", "Chrome launched", "Contains 'Due'"
  actionType: string; // e.g., "send_message", "speak_reminder", "device_action", "ai_task", "take_photo"
  actionValue: string;
  isEnabled: boolean;
  lastFired?: string;
  runCount: number;
}

export interface SheetRow {
  id: string;
  category: string;
  name: string;
  value: string;
  description: string;
  tags?: string;
  updatedAt: string;
}

export interface KnowledgeCategory {
  id: string;
  name: string;
  description: string;
}

export interface KnowledgeItem {
  id: string;
  categoryId: string;
  key: string;
  fact: string;
  savedAt: string;
}

export interface TouchStep {
  x: number; // percentage 0-100
  y: number; // percentage 0-100
  action: 'tap' | 'type' | 'scroll' | 'long_press';
  label: string;
  value?: string;
  delayMs: number;
}

export interface TouchMacro {
  id: string;
  name: string;
  targetApp: string;
  steps: TouchStep[];
  createdAt: string;
}

export interface ScreenshotItem {
  id: string;
  url: string;
  title: string;
  timestamp: string;
}

export interface IncomingCallState {
  active: boolean;
  callerName: string;
  callerNumber: string;
  status: 'ringing' | 'answered' | 'declined';
}

export interface PendingConfirmation {
  id: string;
  title: string;
  details: string;
  actionName: string;
  actionArgs: any;
}

export interface AppSettings {
  assistantName: string; // e.g. "Saara"
  apiKey: string;
  model: string;
  voice: string;
  micEnabled: boolean;
  privacyMode: boolean;
  autoPrivacyForAutomation: boolean; // Auto bypass privacy during complex automation so cloud vision works
  requireCommandConfirmation: boolean; // Confirm before executing direct command tasks
  wakeWordFilterEnabled: boolean; // Only process voice after assistant name
  hotwordsPhraseEnabled: boolean; // Wake up when hotword is detected
  hotwordsPhrase: string; // "Hey Saara"
  voiceMatchEnabled: boolean; // Only respond to enrolled voice
  voiceMatchScore: number; // Enrolled voice pitch/timbre fingerprint (Hz)
  wakeupOnIncomingCall: boolean; // Auto wake up on incoming calls
  floatingBubble: boolean;
  captionOverlay: boolean;
  language: string;
  autoSpeak: boolean;
}
