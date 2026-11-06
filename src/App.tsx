import React, { useState, useEffect, useRef } from 'react';
import {
  Mic,
  MicOff,
  Send,
  Sparkles,
  Settings,
  ShieldCheck,
  ShieldAlert,
  Sliders,
  Volume2,
  VolumeX,
  Camera,
  Monitor,
  BookOpen,
  Trash2,
  RefreshCw,
  Zap,
  Smartphone,
  FileSpreadsheet,
  PhoneCall,
  Clock,
  Layers,
  ArrowUpRight,
  Download
} from 'lucide-react';
import {
  Message,
  DeviceState,
  ScheduledTask,
  KnowledgeCategory,
  KnowledgeItem,
  MacroRule,
  SheetRow,
  TouchMacro,
  ScreenshotItem,
  IncomingCallState,
  PendingConfirmation,
  AppSettings,
  AIEmotion
} from './types';
import { AudioWaveform } from './components/AudioWaveform';
import { DevicePanel } from './components/DevicePanel';
import { LiveCameraOverlay } from './components/LiveCameraOverlay';
import { MobileDeviceSimulator } from './components/MobileDeviceSimulator';
import { AutomationMacrosTab } from './components/AutomationMacrosTab';
import { GoogleSheetsTab } from './components/GoogleSheetsTab';
import { KnowledgeManagerTab } from './components/KnowledgeManagerTab';
import { IncomingCallModal } from './components/IncomingCallModal';
import { CommandConfirmationModal } from './components/CommandConfirmationModal';
import { VoiceMatchModal } from './components/VoiceMatchModal';
import { SettingsModal } from './components/SettingsModal';
import { FloatingBubble } from './components/FloatingBubble';
import { CaptionOverlay } from './components/CaptionOverlay';
import { InstallAppModal } from './components/InstallAppModal';
import { evaluatePrivacyGuard, getEmotionEmoji } from './services/privacyGuard';
import {
  startSpeechRecognition,
  stopSpeechRecognition,
  speakText,
  stopSpeaking,
  playChime,
  filterWakeWord,
  checkHotwordSpoken
} from './services/speechService';

const DEFAULT_SETTINGS: AppSettings = {
  assistantName: 'Saara',
  apiKey: '',
  model: 'gemini-2.5-flash',
  voice: 'Aoede',
  micEnabled: true,
  privacyMode: true,
  autoPrivacyForAutomation: true,
  requireCommandConfirmation: true,
  wakeWordFilterEnabled: false,
  hotwordsPhraseEnabled: true,
  hotwordsPhrase: 'Hey Saara',
  voiceMatchEnabled: false,
  voiceMatchScore: 160,
  wakeupOnIncomingCall: true,
  floatingBubble: true,
  captionOverlay: true,
  language: 'en-US',
  autoSpeak: true,
};

const DEFAULT_CATEGORIES: KnowledgeCategory[] = [
  { id: 'cat-personal', name: 'Personal & Habits', description: 'Everyday preferences, diet, routines' },
  { id: 'cat-work', name: 'Work & Business', description: 'Business rules, meeting hours, leads' },
  { id: 'cat-contacts', name: 'VIP Contacts & Reminders', description: 'Mom, Key Clients, Team' }
];

const DEFAULT_KNOWLEDGE_ITEMS: KnowledgeItem[] = [
  { id: 'k-1', categoryId: 'cat-personal', key: 'morning_coffee', fact: 'Loves black Americano at 8:30 AM with no sugar', savedAt: 'Nov 4, 2026' },
  { id: 'k-2', categoryId: 'cat-work', key: 'client_lead_vip', fact: 'VIP Client Nirmal prefers weekly Tuesday report via WhatsApp', savedAt: 'Nov 4, 2026' },
  { id: 'k-3', categoryId: 'cat-contacts', key: 'mom_birthday', fact: 'Mom birthday is on December 14th. Send bouquet & call early morning.', savedAt: 'Nov 4, 2026' }
];

const DEFAULT_SHEET_ROWS: SheetRow[] = [
  { id: 'sr-1', category: 'Products & Pricing', name: 'Gold Membership Tier', value: '$249', description: 'Annual VIP access + priority support', updatedAt: 'Nov 4, 2026' },
  { id: 'sr-2', category: 'Products & Pricing', name: 'Silver Membership Tier', value: '$149', description: 'Standard yearly software license', updatedAt: 'Nov 4, 2026' },
  { id: 'sr-3', category: 'Products & Pricing', name: 'Starter Tier Package', value: '$49', description: 'Monthly entry tier', updatedAt: 'Nov 4, 2026' },
  { id: 'sr-4', category: 'Customer Balances', name: 'Client Apex Corp', value: '$1,200 Due', description: 'Invoice #104 pending payment - Due Nov 10', updatedAt: 'Nov 4, 2026' },
  { id: 'sr-5', category: 'Customer Balances', name: 'Client Zenith Labs', value: '$450 Due', description: 'Invoice #109 sent - Due Nov 15', updatedAt: 'Nov 4, 2026' }
];

const DEFAULT_MACROS: MacroRule[] = [
  {
    id: 'macro-1',
    name: 'Daily Customer Due Reminder',
    description: 'Send payment reminder to customers with pending balances at 10:00 AM daily',
    triggerType: 'time',
    triggerValue: '10:00 AM Daily',
    actionType: 'send_message',
    actionValue: 'Send WhatsApp statement to Client #104 with attached invoice',
    isEnabled: true,
    runCount: 3,
    lastFired: 'Today 10:00 AM'
  },
  {
    id: 'macro-2',
    name: '5:00 PM Medicine & Health Reminder',
    description: 'Autonomous voice notification to take vitamins and medicine',
    triggerType: 'time',
    triggerValue: '5:00 PM',
    actionType: 'speak_reminder',
    actionValue: 'Time to take your afternoon vitamins and water',
    isEnabled: true,
    runCount: 5,
    lastFired: 'Yesterday 5:00 PM'
  },
  {
    id: 'macro-3',
    name: 'Mom Birthday Greeting Task',
    description: 'On Dec 14, send warm birthday greetings to Mom and launch call',
    triggerType: 'date',
    triggerValue: 'Dec 14, 2026',
    actionType: 'send_message',
    actionValue: 'Happy Birthday Mom! Wishing you joy and happiness always.',
    isEnabled: true,
    runCount: 1,
    lastFired: 'Dec 14, 2025'
  },
  {
    id: 'macro-4',
    name: 'Low Battery Auto Power Saver',
    description: 'When battery drops below 20%, turn off torch, mute ringer, and alert user',
    triggerType: 'system_settings',
    triggerValue: 'Battery < 20%',
    actionType: 'device_action',
    actionValue: 'Turn off torch & mute volume',
    isEnabled: true,
    runCount: 2,
    lastFired: '2 days ago'
  }
];

export const App: React.FC = () => {
  // Navigation active tab
  const [activeTab, setActiveTab] = useState<'chat' | 'automation' | 'simulator' | 'sheets' | 'knowledge'>('chat');

  // Settings
  const [settings, setSettings] = useState<AppSettings>(() => {
    try {
      const s = localStorage.getItem('saara_settings_v2');
      return s ? { ...DEFAULT_SETTINGS, ...JSON.parse(s) } : DEFAULT_SETTINGS;
    } catch {
      return DEFAULT_SETTINGS;
    }
  });

  // State
  const [emotion, setEmotion] = useState<AIEmotion>('happy');
  const [messages, setMessages] = useState<Message[]>([
    {
      id: 'm-init',
      role: 'system',
      text: `${settings.assistantName} v2.1.1 online. Human-like device automation, MacroDroid triggers, and Google Sheets connected.`,
      timestamp: new Date().toLocaleString([], { dateStyle: 'medium', timeStyle: 'short' }),
      emotion: 'happy'
    },
    {
      id: 'm-hello',
      role: 'saara',
      text: `Hello! I am ${settings.assistantName}. I can browse Chrome, interact with WhatsApp, attach documents, manage MacroDroid triggers, and query your Google Sheets.`,
      timestamp: new Date().toLocaleString([], { dateStyle: 'medium', timeStyle: 'short' }),
      emotion: 'happy'
    }
  ]);

  const [deviceState, setDeviceState] = useState<DeviceState>({
    flashlight: false,
    volume: 75,
    wifi: true,
    bluetooth: true,
    muted: false,
    battery: 88,
    charging: false,
    activeApp: 'home',
    activeTimer: null
  });

  // Knowledge & Categories
  const [categories, setCategories] = useState<KnowledgeCategory[]>(() => {
    try {
      const s = localStorage.getItem('saara_categories');
      return s ? JSON.parse(s) : DEFAULT_CATEGORIES;
    } catch {
      return DEFAULT_CATEGORIES;
    }
  });

  const [knowledgeItems, setKnowledgeItems] = useState<KnowledgeItem[]>(() => {
    try {
      const s = localStorage.getItem('saara_knowledge_items');
      return s ? JSON.parse(s) : DEFAULT_KNOWLEDGE_ITEMS;
    } catch {
      return DEFAULT_KNOWLEDGE_ITEMS;
    }
  });

  // Sheets data
  const [sheetRows, setSheetRows] = useState<SheetRow[]>(() => {
    try {
      const s = localStorage.getItem('saara_sheets_data');
      return s ? JSON.parse(s) : DEFAULT_SHEET_ROWS;
    } catch {
      return DEFAULT_SHEET_ROWS;
    }
  });

  // Macros
  const [macros, setMacros] = useState<MacroRule[]>(() => {
    try {
      const s = localStorage.getItem('saara_macros');
      return s ? JSON.parse(s) : DEFAULT_MACROS;
    } catch {
      return DEFAULT_MACROS;
    }
  });

  // Touch Macros & Screenshots
  const [touchMacros, setTouchMacros] = useState<TouchMacro[]>([]);
  const [screenshots, setScreenshots] = useState<ScreenshotItem[]>([]);
  const [externalTouchPos, setExternalTouchPos] = useState<{ x: number; y: number; active: boolean } | null>(null);

  // Incoming Call State
  const [callState, setCallState] = useState<IncomingCallState>({
    active: false,
    callerName: 'Mom',
    callerNumber: '+1 (555) 019-2834',
    status: 'ringing'
  });

  // Pending Confirmation
  const [pendingConfirmation, setPendingConfirmation] = useState<PendingConfirmation | null>(null);

  // Speech and voice states
  const [inputText, setInputText] = useState('');
  const [interimSpeech, setInterimSpeech] = useState('');
  const [isListening, setIsListening] = useState(false);
  const [isSpeaking, setIsSpeaking] = useState(false);
  const [isGenerating, setIsGenerating] = useState(false);
  const [statusText, setStatusText] = useState('Connected · Active');

  // Modals & Overlays
  const [cameraOverlayOpen, setCameraOverlayOpen] = useState(false);
  const [showSettingsModal, setShowSettingsModal] = useState(false);
  const [showVoiceMatchModal, setShowVoiceMatchModal] = useState(false);
  const [showLiveScreenRequest, setShowLiveScreenRequest] = useState(false);
  const [showInstallModal, setShowInstallModal] = useState(false);
  const [deferredPrompt, setDeferredPrompt] = useState<any>(null);

  useEffect(() => {
    const handleBeforeInstall = (e: any) => {
      e.preventDefault();
      setDeferredPrompt(e);
    };
    window.addEventListener('beforeinstallprompt', handleBeforeInstall);
    return () => window.removeEventListener('beforeinstallprompt', handleBeforeInstall);
  }, []);

  const messagesEndRef = useRef<HTMLDivElement | null>(null);

  // Sync settings
  useEffect(() => {
    localStorage.setItem('saara_settings_v2', JSON.stringify(settings));
  }, [settings]);

  // Sync categories & items
  useEffect(() => {
    localStorage.setItem('saara_categories', JSON.stringify(categories));
    localStorage.setItem('saara_knowledge_items', JSON.stringify(knowledgeItems));
  }, [categories, knowledgeItems]);

  // Sync sheet rows & macros
  useEffect(() => {
    localStorage.setItem('saara_sheets_data', JSON.stringify(sheetRows));
    localStorage.setItem('saara_macros', JSON.stringify(macros));
  }, [sheetRows, macros]);

  // Scroll messages
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages, interimSpeech]);

  // Active Timer Countdown
  useEffect(() => {
    if (!deviceState.activeTimer) return;
    const interval = setInterval(() => {
      setDeviceState(prev => {
        if (!prev.activeTimer) return prev;
        if (prev.activeTimer.remaining <= 1) {
          addMessage('system', `⏰ Timer finished: ${prev.activeTimer.description || 'Countdown'}`);
          setEmotion('happy');
          playChime('alert');
          if (settings.autoSpeak) {
            speakText(`Timer complete: ${prev.activeTimer.description || 'Your timer is done'}`);
          }
          return { ...prev, activeTimer: null };
        }
        return {
          ...prev,
          activeTimer: {
            ...prev.activeTimer,
            remaining: prev.activeTimer.remaining - 1
          }
        };
      });
    }, 1000);

    return () => clearInterval(interval);
  }, [deviceState.activeTimer, settings.autoSpeak]);

  const addMessage = (role: Message['role'], text: string, toolCall?: Message['toolCall']) => {
    const newMsg: Message = {
      id: `msg-${Date.now()}-${Math.random().toString(36).substr(2, 4)}`,
      role,
      text,
      timestamp: new Date().toLocaleString([], { dateStyle: 'medium', timeStyle: 'short' }),
      emotion,
      toolCall
    };
    setMessages(prev => [...prev, newMsg]);
    return newMsg;
  };

  // Autonomous Device Actions Execution
  const executeAutonomousTool = (name: string, args: any): string => {
    addMessage('tool', `⚙️ ${name} ${JSON.stringify(args || {})}`);

    if (name === 'device_touch') {
      const app = args?.app || 'chrome';
      const action = args?.action || 'tap';
      const val = args?.value || '';

      setDeviceState(prev => ({ ...prev, activeApp: app }));

      // Trigger visual touch ripple on screen
      const x = args?.x || (action === 'attach_document' ? 20 : 50);
      const y = args?.y || (action === 'attach_document' ? 90 : 50);
      setExternalTouchPos({ x, y, active: true });
      setTimeout(() => setExternalTouchPos(null), 1200);

      if (action === 'attach_document') {
        return `Tapped WhatsApp Paperclip (📎) → Selected Document (${val || 'Invoice.pdf'})`;
      }
      if (action === 'search_chrome') {
        return `Navigated Chrome → Searched: "${val}" → Extracted pricing data`;
      }
      return `Interacted with ${app} on screen (${action})`;
    }

    if (name === 'capture_screenshot') {
      const title = args?.title || 'Autonomous Screen Capture';
      handleCaptureScreenshot(title);
      return `Screenshot captured and stored in device gallery: "${title}"`;
    }

    if (name === 'google_sheets_query') {
      const term = (args?.query_term || '').toLowerCase();
      const matches = sheetRows.filter(r =>
        r.name.toLowerCase().includes(term) ||
        r.category.toLowerCase().includes(term) ||
        r.description.toLowerCase().includes(term)
      );
      if (matches.length > 0) {
        return `Google Sheet query match: ${matches.map(m => `[${m.name}: ${m.value}]`).join(', ')}`;
      }
      return `Google Sheet queried: Retrieved active catalog of ${sheetRows.length} items`;
    }

    if (name === 'device_control') {
      const action = args?.action || '';
      const value = args?.value || '';

      switch (action) {
        case 'flashlight_on':
          setDeviceState(prev => ({ ...prev, flashlight: true }));
          return 'Torch turned ON';
        case 'flashlight_off':
          setDeviceState(prev => ({ ...prev, flashlight: false }));
          return 'Torch turned OFF';
        case 'toggle_flashlight':
          setDeviceState(prev => ({ ...prev, flashlight: !prev.flashlight }));
          return 'Torch toggled';
        case 'set_volume': {
          const num = parseInt(value, 10);
          const vol = isNaN(num) ? 75 : Math.max(0, Math.min(100, num));
          setDeviceState(prev => ({ ...prev, volume: vol }));
          return `Media volume set to ${vol}%`;
        }
        case 'take_photo':
          setCameraOverlayOpen(true);
          return 'Opened live camera stream on screen';
        case 'start_timer': {
          const secs = parseInt(value, 10) || 180;
          setDeviceState(prev => ({
            ...prev,
            activeTimer: { duration: secs, remaining: secs, description: 'Timer' }
          }));
          return `Started timer for ${Math.round(secs / 60)} minutes`;
        }
        default:
          return `Action ${action} handled on device`;
      }
    }

    if (name === 'schedule_task') {
      const desc = args?.task_description || 'Reminder';
      const time = args?.time_or_delay || 'Soon';
      const newMacro: MacroRule = {
        id: `macro-${Date.now()}`,
        name: desc,
        description: `Triggered at ${time}`,
        triggerType: 'time',
        triggerValue: time,
        actionType: 'speak_reminder',
        actionValue: desc,
        isEnabled: true,
        runCount: 0
      };
      setMacros(prev => [newMacro, ...prev]);
      return `Scheduled task created in MacroDroid hub: "${desc}" (${time})`;
    }

    return `Autonomous action ${name} finished`;
  };

  // Main input processor
  const processInput = async (rawText: string, isFromAutomation = false) => {
    let text = rawText.trim();
    if (!text) return;

    // Wake word filtering check
    if (settings.wakeWordFilterEnabled && !isFromAutomation) {
      const filterResult = filterWakeWord(text, settings.assistantName, true);
      if (!filterResult.matched) {
        addMessage('system', `Ignored voice input: Assistant name "${settings.assistantName}" was not heard.`);
        return;
      }
      text = filterResult.command;
    }

    addMessage('you', text);
    setInputText('');
    setInterimSpeech('');

    // Check Command Confirmation Requirement (Only for user direct commands, NOT automation)
    if (settings.requireCommandConfirmation && !isFromAutomation) {
      const isDirectHardwareOrDelete =
        text.toLowerCase().includes('delete') ||
        text.toLowerCase().includes('wipe') ||
        text.toLowerCase().includes('call') ||
        text.toLowerCase().includes('flashlight') ||
        text.toLowerCase().includes('torch') ||
        text.toLowerCase().includes('volume');

      if (isDirectHardwareOrDelete) {
        setPendingConfirmation({
          id: `conf-${Date.now()}`,
          title: `Confirm Action: "${text}"`,
          details: `Saara requires confirmation before modifying phone settings or executing commands.`,
          actionName: 'command_execution',
          actionArgs: { command: text }
        });
        return;
      }
    }

    // Execute through pipeline
    executeDispatch(text);
  };

  const executeDispatch = async (text: string) => {
    // Privacy Guard Evaluation with Dynamic Automation Bypass
    const privacyCheck = evaluatePrivacyGuard(text, settings.privacyMode, settings.autoPrivacyForAutomation);
    if (privacyCheck.isSensitive) {
      setEmotion('protective');
      playChime('alert');
      addMessage(
        'saara',
        'This request appears to involve private information. I kept it on this device and did not send it to Gemini.'
      );
      addMessage('system', `🔒 Privacy Shield: Guarded "${privacyCheck.matchedPattern || 'sensitive data'}".`);
      if (settings.autoSpeak) {
        speakText('This request appears to involve private information. I kept it on this device and did not send it to Gemini.');
      }
      return;
    }

    if (privacyCheck.bypassedForAutomation) {
      addMessage('system', '⚡ High-Performance Vision Automation Active: Screen & data shared with AI for task completion.');
    }

    // Send to Gemini Backend
    setIsGenerating(true);
    setEmotion('thinking');
    setStatusText(`${settings.assistantName} is thinking…`);

    try {
      const res = await fetch('/api/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          message: text,
          history: messages.slice(-10),
          model: settings.model,
          apiKey: settings.apiKey || undefined,
          assistantName: settings.assistantName,
          sheetContext: sheetRows
        })
      });

      const data = await res.json();
      setIsGenerating(false);

      if (data.emotion) {
        setEmotion(data.emotion);
      } else {
        setEmotion('happy');
      }

      // Execute function calls
      if (Array.isArray(data.functionCalls) && data.functionCalls.length > 0) {
        for (const call of data.functionCalls) {
          const result = executeAutonomousTool(call.name, call.args);
          addMessage('tool', `→ ${result}`);
        }
      }

      if (data.reply) {
        addMessage('saara', data.reply);
        setStatusText('Connected · Active');

        if (settings.autoSpeak) {
          setIsSpeaking(true);
          speakText(
            data.reply,
            () => {
              setIsSpeaking(false);
            },
            settings.language
          );
        }
      } else if (data.functionCalls?.length > 0) {
        setStatusText('Task completed autonomously');
        playChime('success');
      }
    } catch (err: any) {
      setIsGenerating(false);
      setEmotion('sad');
      setStatusText('Error');
      addMessage('system', `Error: ${err?.message || 'Could not reach assistant'}`);
    }
  };

  // Toggle Microphone
  const toggleMic = () => {
    if (isListening) {
      stopSpeechRecognition();
      setIsListening(false);
      setStatusText('Microphone muted');
      addMessage('system', 'Microphone muted.');
    } else {
      stopSpeaking();
      const started = startSpeechRecognition({
        onResult: (transcript, isFinal) => {
          // Hotword detection check
          if (settings.hotwordsPhraseEnabled && checkHotwordSpoken(transcript, settings.hotwordsPhrase, settings.assistantName)) {
            playChime('start');
            setEmotion('thinking');
          }

          if (isFinal) {
            processInput(transcript);
          } else {
            setInterimSpeech(transcript);
          }
        },
        onError: (err) => {
          setIsListening(false);
          setStatusText('Mic Error');
          addMessage('system', `Microphone: ${err}`);
        },
        onEnd: () => {
          setIsListening(false);
          setStatusText('Connected · Active');
        },
        language: settings.language
      });

      if (started) {
        setIsListening(true);
        setStatusText('Listening… speak naturally');
        addMessage('system', 'Microphone active.');
      }
    }
  };

  // Fire Macro Trigger (No confirmation required!)
  const handleFireMacro = (macro: MacroRule) => {
    playChime('success');
    setEmotion('focused');
    addMessage('system', `⚡ Trigger Fired: [${macro.name}] (${macro.triggerType}: ${macro.triggerValue})`);

    // Increment run count
    setMacros(prev =>
      prev.map(m =>
        m.id === macro.id
          ? { ...m, runCount: m.runCount + 1, lastFired: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) }
          : m
      )
    );

    // Execute macro action autonomously
    if (macro.actionType === 'speak_reminder') {
      addMessage('saara', `📢 Trigger Alert: ${macro.actionValue}`);
      if (settings.autoSpeak) {
        speakText(macro.actionValue);
      }
    } else if (macro.actionType === 'send_message') {
      addMessage('tool', `⚙️ WhatsApp / SMS message dispatched: "${macro.actionValue}"`);
      addMessage('saara', `Autonomous macro executed: Sent message "${macro.actionValue}"`);
      // Update phone simulator app to WhatsApp
      setDeviceState(prev => ({ ...prev, activeApp: 'whatsapp' }));
    } else if (macro.actionType === 'device_action') {
      executeAutonomousTool('device_control', { action: 'toggle_flashlight' });
    } else {
      executeDispatch(macro.actionValue);
    }
  };

  // Screenshot capture
  const handleCaptureScreenshot = (title = 'Screenshot') => {
    const canvas = document.createElement('canvas');
    canvas.width = 360;
    canvas.height = 640;
    const ctx = canvas.getContext('2d');
    if (ctx) {
      ctx.fillStyle = '#0f172a';
      ctx.fillRect(0, 0, 360, 640);
      ctx.fillStyle = '#6366f1';
      ctx.font = 'bold 16px sans-serif';
      ctx.fillText(`Saara Screen Snapshot`, 24, 60);
      ctx.fillStyle = '#94a3b8';
      ctx.font = '12px sans-serif';
      ctx.fillText(`${title} • ${new Date().toLocaleTimeString()}`, 24, 90);
      ctx.fillStyle = '#1e293b';
      ctx.roundRect(24, 120, 312, 480, 16);
      ctx.fill();
    }
    const dataUrl = canvas.toDataURL('image/png');
    const newSnap: ScreenshotItem = {
      id: `snap-${Date.now()}`,
      url: dataUrl,
      title,
      timestamp: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
    };
    setScreenshots(prev => [newSnap, ...prev]);
    playChime('click');
  };

  const latestMsg = messages[messages.length - 1] || null;

  return (
    <div className="flex flex-col h-screen bg-slate-950 text-slate-100 font-sans overflow-hidden">
      {/* Top Header */}
      <header className="px-4 py-2.5 bg-slate-900/90 border-b border-slate-800 backdrop-blur-md flex items-center justify-between z-30 shrink-0">
        <div className="flex items-center gap-3">
          <div className="relative cursor-pointer" onClick={toggleMic}>
            <div className="w-9 h-9 rounded-xl bg-gradient-to-tr from-indigo-600 via-indigo-500 to-purple-500 flex items-center justify-center shadow-lg shadow-indigo-500/20">
              <Sparkles className="w-5 h-5 text-white" />
            </div>
            <span
              className={`absolute -bottom-0.5 -right-0.5 w-3 h-3 rounded-full border-2 border-slate-900 ${
                isListening
                  ? 'bg-emerald-400 animate-pulse'
                  : isSpeaking
                  ? 'bg-purple-400 animate-ping'
                  : 'bg-indigo-400'
              }`}
            />
          </div>

          <div>
            <div className="flex items-center gap-2">
              <h1 className="font-bold text-sm tracking-tight text-white">{settings.assistantName}</h1>
              <span className="text-[10px] font-mono px-1.5 py-0.5 rounded-md bg-indigo-500/10 text-indigo-400 border border-indigo-500/20">
                v2.1.1
              </span>
              {/* Emotion Indicator Badge */}
              <span className="text-xs px-2 py-0.5 rounded-full bg-slate-800 border border-slate-700 text-slate-200 flex items-center gap-1 font-semibold">
                {getEmotionEmoji(emotion)}
              </span>
            </div>
            <p className="text-[11px] text-slate-400 flex items-center gap-1.5">
              <span className="w-1.5 h-1.5 rounded-full bg-emerald-400" />
              {statusText}
            </p>
          </div>
        </div>

        {/* Tab Navigation Switcher */}
        <div className="hidden md:flex items-center gap-1 bg-slate-950 p-1 rounded-xl border border-slate-800">
          <button
            onClick={() => setActiveTab('chat')}
            className={`px-3 py-1.5 rounded-lg text-xs font-semibold transition ${
              activeTab === 'chat'
                ? 'bg-indigo-600 text-white shadow-md'
                : 'text-slate-400 hover:text-slate-200'
            }`}
          >
            Assistant Chat
          </button>
          <button
            onClick={() => setActiveTab('automation')}
            className={`px-3 py-1.5 rounded-lg text-xs font-semibold flex items-center gap-1 transition ${
              activeTab === 'automation'
                ? 'bg-indigo-600 text-white shadow-md'
                : 'text-slate-400 hover:text-slate-200'
            }`}
          >
            <Zap className="w-3.5 h-3.5 text-amber-300" />
            Automation
          </button>
          <button
            onClick={() => setActiveTab('simulator')}
            className={`px-3 py-1.5 rounded-lg text-xs font-semibold flex items-center gap-1 transition ${
              activeTab === 'simulator'
                ? 'bg-indigo-600 text-white shadow-md'
                : 'text-slate-400 hover:text-slate-200'
            }`}
          >
            <Smartphone className="w-3.5 h-3.5 text-indigo-400" />
            Mobile Simulator
          </button>
          <button
            onClick={() => setActiveTab('sheets')}
            className={`px-3 py-1.5 rounded-lg text-xs font-semibold flex items-center gap-1 transition ${
              activeTab === 'sheets'
                ? 'bg-indigo-600 text-white shadow-md'
                : 'text-slate-400 hover:text-slate-200'
            }`}
          >
            <FileSpreadsheet className="w-3.5 h-3.5 text-emerald-400" />
            Google Sheets
          </button>
          <button
            onClick={() => setActiveTab('knowledge')}
            className={`px-3 py-1.5 rounded-lg text-xs font-semibold flex items-center gap-1 transition ${
              activeTab === 'knowledge'
                ? 'bg-indigo-600 text-white shadow-md'
                : 'text-slate-400 hover:text-slate-200'
            }`}
          >
            <BookOpen className="w-3.5 h-3.5 text-purple-400" />
            Vault
          </button>
        </div>

        {/* Right utility buttons */}
        <div className="flex items-center gap-1.5">
          {/* Download & Install on Mobile button */}
          <button
            onClick={() => setShowInstallModal(true)}
            className="px-2.5 sm:px-3 py-1.5 rounded-xl bg-gradient-to-r from-indigo-600 to-purple-600 hover:from-indigo-500 hover:to-purple-500 text-white text-xs font-semibold flex items-center gap-1.5 shadow-md shadow-indigo-600/30 transition"
            title="Download & Install App on Mobile Phone"
          >
            <Download className="w-3.5 h-3.5" />
            <span className="hidden sm:inline">Install on Phone</span>
            <span className="sm:hidden">Install</span>
          </button>

          {/* Incoming Call Simulator button */}
          <button
            onClick={() => {
              setCallState({
                active: true,
                callerName: 'Mom',
                callerNumber: '+1 (555) 019-2834',
                status: 'ringing'
              });
            }}
            className="px-2.5 py-1.5 rounded-lg bg-emerald-600/20 hover:bg-emerald-600/30 border border-emerald-500/40 text-emerald-300 text-xs font-medium flex items-center gap-1 transition"
            title="Simulate Incoming Call"
          >
            <PhoneCall className="w-3.5 h-3.5 text-emerald-400 animate-pulse" />
            <span className="hidden sm:inline">Test Call</span>
          </button>

          {/* Camera stream toggle */}
          <button
            onClick={() => setCameraOverlayOpen(prev => !prev)}
            className={`p-2 rounded-lg transition ${
              cameraOverlayOpen
                ? 'bg-indigo-600 text-white'
                : 'text-slate-400 hover:text-white hover:bg-slate-800'
            }`}
            title="Toggle Live Camera Overlay"
          >
            <Camera className="w-4 h-4" />
          </button>

          {/* Settings */}
          <button
            onClick={() => setShowSettingsModal(true)}
            className="p-2 rounded-lg text-slate-400 hover:text-white hover:bg-slate-800 transition"
            title="Open Settings"
          >
            <Settings className="w-4 h-4" />
          </button>
        </div>
      </header>

      {/* Mobile Tab Navigation for small screens */}
      <div className="flex md:hidden bg-slate-900 border-b border-slate-800 px-2 py-1 justify-around text-xs shrink-0">
        <button
          onClick={() => setActiveTab('chat')}
          className={`py-1 px-2 font-medium ${activeTab === 'chat' ? 'text-indigo-400 border-b-2 border-indigo-500' : 'text-slate-400'}`}
        >
          Chat
        </button>
        <button
          onClick={() => setActiveTab('automation')}
          className={`py-1 px-2 font-medium ${activeTab === 'automation' ? 'text-indigo-400 border-b-2 border-indigo-500' : 'text-slate-400'}`}
        >
          Automation
        </button>
        <button
          onClick={() => setActiveTab('simulator')}
          className={`py-1 px-2 font-medium ${activeTab === 'simulator' ? 'text-indigo-400 border-b-2 border-indigo-500' : 'text-slate-400'}`}
        >
          Mobile
        </button>
        <button
          onClick={() => setActiveTab('sheets')}
          className={`py-1 px-2 font-medium ${activeTab === 'sheets' ? 'text-indigo-400 border-b-2 border-indigo-500' : 'text-slate-400'}`}
        >
          Sheets
        </button>
        <button
          onClick={() => setActiveTab('knowledge')}
          className={`py-1 px-2 font-medium ${activeTab === 'knowledge' ? 'text-indigo-400 border-b-2 border-indigo-500' : 'text-slate-400'}`}
        >
          Vault
        </button>
      </div>

      {/* Main Content Area */}
      <div className="flex-1 overflow-hidden relative">
        {/* TAB 1: ASSISTANT CHAT */}
        {activeTab === 'chat' && (
          <div className="flex flex-col lg:flex-row h-full overflow-hidden">
            {/* Messages Chat Bus */}
            <div className="flex-1 flex flex-col h-full overflow-hidden border-b lg:border-b-0 lg:border-r border-slate-800">
              <div className="flex-1 p-4 overflow-y-auto space-y-3.5">
                {messages.map((m) => {
                  if (m.role === 'system') {
                    return (
                      <div key={m.id} className="flex justify-center my-1.5">
                        <span className="text-[11px] font-mono px-3 py-1 rounded-full bg-slate-900 border border-slate-800 text-slate-400 max-w-lg text-center">
                          ℹ️ {m.text} • <span className="text-[10px] text-slate-500">{m.timestamp}</span>
                        </span>
                      </div>
                    );
                  }

                  if (m.role === 'tool') {
                    return (
                      <div key={m.id} className="flex justify-center my-1">
                        <div className="text-[11px] font-mono px-3 py-1 rounded-lg bg-indigo-950/40 border border-indigo-800/40 text-indigo-300 max-w-lg">
                          {m.text}
                        </div>
                      </div>
                    );
                  }

                  const isUser = m.role === 'you';
                  return (
                    <div
                      key={m.id}
                      className={`flex items-start gap-2.5 ${isUser ? 'flex-row-reverse' : 'flex-row'}`}
                    >
                      <div
                        className={`w-7 h-7 rounded-lg flex items-center justify-center shrink-0 text-xs font-bold ${
                          isUser
                            ? 'bg-indigo-600 text-white'
                            : 'bg-purple-600 text-white shadow-md shadow-purple-500/20'
                        }`}
                      >
                        {isUser ? '🧑' : '✨'}
                      </div>

                      <div
                        className={`max-w-[80%] rounded-2xl px-4 py-2.5 text-xs sm:text-sm leading-relaxed shadow-sm ${
                          isUser
                            ? 'bg-indigo-600 text-white rounded-tr-none'
                            : 'bg-slate-900 border border-slate-800 text-slate-200 rounded-tl-none'
                        }`}
                      >
                        <div className="whitespace-pre-wrap">{m.text}</div>
                        {/* Full Date and Time displayed on every message card as requested */}
                        <div
                          className={`text-[10px] mt-1.5 flex items-center gap-1.5 ${
                            isUser ? 'text-indigo-200 justify-end' : 'text-slate-500 justify-start'
                          }`}
                        >
                          <Clock className="w-2.5 h-2.5" />
                          <span>{m.timestamp}</span>
                          {m.emotion && !isUser && (
                            <span className="ml-1 text-[10px] font-semibold text-indigo-300">
                              {getEmotionEmoji(m.emotion)}
                            </span>
                          )}
                        </div>
                      </div>
                    </div>
                  );
                })}

                {interimSpeech && (
                  <div className="flex items-start gap-2.5 flex-row-reverse animate-pulse">
                    <div className="w-7 h-7 rounded-lg bg-indigo-500/50 flex items-center justify-center text-xs">
                      🧑
                    </div>
                    <div className="max-w-[80%] rounded-2xl px-4 py-2.5 text-xs sm:text-sm bg-indigo-900/60 border border-indigo-700/50 text-indigo-100 rounded-tr-none italic">
                      "{interimSpeech}..."
                    </div>
                  </div>
                )}

                {isGenerating && (
                  <div className="flex items-center gap-2 text-xs text-indigo-400 pl-9">
                    <Sparkles className="w-4 h-4 animate-spin text-indigo-400" />
                    <span>{settings.assistantName} is thinking & automating…</span>
                  </div>
                )}

                <div ref={messagesEndRef} />
              </div>

              {/* Input Bar */}
              <div className="p-3 bg-slate-900/90 border-t border-slate-800 shrink-0">
                <form
                  onSubmit={(e) => {
                    e.preventDefault();
                    processInput(inputText);
                  }}
                  className="flex items-center gap-2"
                >
                  <button
                    type="button"
                    onClick={toggleMic}
                    className={`p-2.5 rounded-xl transition ${
                      isListening
                        ? 'bg-emerald-500 text-slate-950 hover:bg-emerald-400 animate-pulse'
                        : 'bg-slate-800 text-slate-300 hover:bg-slate-700'
                    }`}
                    title={isListening ? 'Mute Microphone' : 'Start Voice Input'}
                  >
                    {isListening ? <Mic className="w-5 h-5" /> : <MicOff className="w-5 h-5" />}
                  </button>

                  <input
                    type="text"
                    placeholder={`Talk with ${settings.assistantName} (e.g. "go to chrome and search", "attach WhatsApp document", "check product price")...`}
                    value={inputText}
                    onChange={(e) => setInputText(e.target.value)}
                    className="flex-1 bg-slate-950 border border-slate-800 rounded-xl px-4 py-2.5 text-xs sm:text-sm text-slate-100 placeholder-slate-500 focus:outline-none focus:border-indigo-500 transition"
                  />

                  <button
                    type="submit"
                    disabled={!inputText.trim()}
                    className="p-2.5 rounded-xl bg-indigo-600 hover:bg-indigo-500 text-white disabled:opacity-40 transition"
                  >
                    <Send className="w-4 h-4" />
                  </button>
                </form>
              </div>
            </div>

            {/* Right Column: Voice Orb & Quick Device Tiles */}
            <div className="w-full lg:w-96 flex flex-col p-4 bg-slate-950/50 overflow-y-auto space-y-4 shrink-0">
              {/* Voice Orb with Emotion Halo */}
              <div className="bg-slate-900/70 border border-slate-800 rounded-2xl p-5 flex flex-col items-center justify-center relative backdrop-blur-sm shadow-xl">
                <div className="relative my-2 cursor-pointer" onClick={toggleMic} title="Click to talk">
                  <div
                    className={`w-24 h-24 rounded-full flex items-center justify-center shadow-2xl transition-all duration-500 ${
                      isSpeaking
                        ? 'bg-gradient-to-tr from-pink-500 via-purple-500 to-indigo-500 scale-105 animate-pulse-ring'
                        : isListening
                        ? 'bg-gradient-to-tr from-emerald-500 via-teal-500 to-cyan-500 scale-105 animate-pulse-ring'
                        : 'bg-gradient-to-tr from-indigo-700 via-indigo-600 to-purple-800 hover:scale-105'
                    }`}
                  >
                    <div className="w-20 h-20 rounded-full bg-slate-950/40 backdrop-blur-md flex items-center justify-center border border-white/20">
                      {isSpeaking ? (
                        <Volume2 className="w-8 h-8 text-white animate-bounce" />
                      ) : isListening ? (
                        <Mic className="w-8 h-8 text-white animate-pulse" />
                      ) : (
                        <Sparkles className="w-8 h-8 text-indigo-300" />
                      )}
                    </div>
                  </div>
                </div>

                <AudioWaveform
                  isActive={isListening}
                  isSpeaking={isSpeaking}
                  statusText={statusText}
                />
              </div>

              {/* Hardware Device Panel */}
              <DevicePanel
                deviceState={deviceState}
                onUpdateDevice={(partial) => setDeviceState(prev => ({ ...prev, ...partial }))}
                onOpenMedia={(mode) => {
                  if (mode === 'camera') setCameraOverlayOpen(true);
                  if (mode === 'screen') setShowLiveScreenRequest(true);
                }}
                onOpenNotebook={() => setActiveTab('knowledge')}
              />

              {/* Quick Macro & Automation shortcuts */}
              <div className="bg-slate-900/40 border border-slate-800/60 rounded-2xl p-3.5 space-y-2">
                <div className="flex items-center justify-between">
                  <p className="text-[11px] font-semibold text-slate-400 uppercase tracking-wider">
                    MacroDroid Fast Triggers
                  </p>
                  <button
                    onClick={() => setActiveTab('automation')}
                    className="text-[10px] text-indigo-400 hover:underline flex items-center gap-0.5"
                  >
                    View All ({macros.length}) <ArrowUpRight className="w-3 h-3" />
                  </button>
                </div>
                <div className="flex flex-col gap-1.5">
                  {macros.slice(0, 3).map((macro) => (
                    <button
                      key={macro.id}
                      onClick={() => handleFireMacro(macro)}
                      className="p-2 rounded-xl bg-slate-800/80 hover:bg-indigo-600/20 border border-slate-700/60 text-left text-xs text-slate-200 flex items-center justify-between group transition"
                    >
                      <div className="truncate">
                        <p className="font-semibold text-[11px] truncate">{macro.name}</p>
                        <p className="text-[9px] text-slate-400 truncate">{macro.triggerValue}</p>
                      </div>
                      <span className="text-[9px] px-2 py-0.5 rounded bg-emerald-500/20 text-emerald-400 font-medium shrink-0 group-hover:bg-emerald-500 group-hover:text-black transition">
                        Fire Now
                      </span>
                    </button>
                  ))}
                </div>
              </div>
            </div>
          </div>
        )}

        {/* TAB 2: AUTOMATION (MACRODROID) */}
        {activeTab === 'automation' && (
          <AutomationMacrosTab
            macros={macros}
            onAddMacro={(newMacro) => setMacros(prev => [newMacro, ...prev])}
            onToggleMacro={(id) =>
              setMacros(prev => prev.map(m => (m.id === id ? { ...m, isEnabled: !m.isEnabled } : m)))
            }
            onDeleteMacro={(id) => setMacros(prev => prev.filter(m => m.id !== id))}
            onFireMacroTrigger={handleFireMacro}
          />
        )}

        {/* TAB 3: MOBILE DEVICE SIMULATOR */}
        {activeTab === 'simulator' && (
          <MobileDeviceSimulator
            activeApp={deviceState.activeApp}
            setActiveApp={(app) => setDeviceState(prev => ({ ...prev, activeApp: app }))}
            onCaptureScreenshot={handleCaptureScreenshot}
            onRequestLiveScreenShare={() => setShowLiveScreenRequest(true)}
            screenshots={screenshots}
            touchMacros={touchMacros}
            onSaveTouchMacro={(macro) => setTouchMacros(prev => [macro, ...prev])}
            onDeleteTouchMacro={(id) => setTouchMacros(prev => prev.filter(m => m.id !== id))}
            externalTouchPosition={externalTouchPos}
          />
        )}

        {/* TAB 4: GOOGLE SHEETS */}
        {activeTab === 'sheets' && (
          <GoogleSheetsTab
            sheetRows={sheetRows}
            onAddRow={(row) => setSheetRows(prev => [row, ...prev])}
            onUpdateRow={(row) => setSheetRows(prev => prev.map(r => (r.id === row.id ? row : r)))}
            onDeleteRow={(id) => setSheetRows(prev => prev.filter(r => r.id !== id))}
            onTrainAIWithSheet={(rows) => {
              addMessage('system', `AI Grounding: Indexed ${rows.length} rows from connected Google Sheet.`);
              setEmotion('happy');
            }}
          />
        )}

        {/* TAB 5: LEARNING & KNOWLEDGE VAULT */}
        {activeTab === 'knowledge' && (
          <KnowledgeManagerTab
            categories={categories}
            items={knowledgeItems}
            onAddCategory={(cat) => setCategories(prev => [...prev, cat])}
            onUpdateCategory={(cat) => setCategories(prev => prev.map(c => (c.id === cat.id ? cat : c)))}
            onDeleteCategory={(id) => setCategories(prev => prev.filter(c => c.id !== id))}
            onAddItem={(item) => setKnowledgeItems(prev => [item, ...prev])}
            onUpdateItem={(item) => setKnowledgeItems(prev => prev.map(i => (i.id === item.id ? item : i)))}
            onDeleteItem={(id) => setKnowledgeItems(prev => prev.filter(i => i.id !== id))}
            onMoveItem={(itemId, newCatId) => {
              setKnowledgeItems(prev =>
                prev.map(i => (i.id === itemId ? { ...i, categoryId: newCatId } : i))
              );
              playChime('click');
            }}
          />
        )}
      </div>

      {/* Persistent Live Camera Overlay Picture-in-Picture */}
      <LiveCameraOverlay
        isOpen={cameraOverlayOpen}
        onClose={() => setCameraOverlayOpen(false)}
        onSnapshot={() => {
          handleCaptureScreenshot('Camera Snapshot');
          addMessage('tool', '📷 Camera snapshot captured locally and stored');
        }}
      />

      {/* Floating Bubble with Emotion Pill */}
      <FloatingBubble
        visible={settings.floatingBubble}
        emotion={emotion}
        isListening={isListening}
        isSpeaking={isSpeaking}
        onToggleMic={toggleMic}
        onClick={() => {
          if (!isListening) toggleMic();
        }}
      />

      {/* Real-time Subtitle / Caption HUD Overlay with Emotion Indicator */}
      <CaptionOverlay
        visible={settings.captionOverlay}
        latestMessage={latestMsg}
        emotion={emotion}
        isListening={isListening}
        isSpeaking={isSpeaking}
        onDismiss={() => setSettings(prev => ({ ...prev, captionOverlay: false }))}
      />

      {/* Incoming Call Voice Wakeup Modal */}
      <IncomingCallModal
        callState={callState}
        assistantName={settings.assistantName}
        autoSpeak={settings.autoSpeak}
        onAnswer={() => {
          setCallState(prev => ({ ...prev, status: 'answered', active: false }));
          addMessage('system', '📞 Call answered via voice command.');
          setEmotion('happy');
        }}
        onDecline={() => {
          setCallState(prev => ({ ...prev, status: 'declined', active: false }));
          addMessage('system', '📞 Call declined via voice command.');
          setEmotion('neutral');
        }}
      />

      {/* Command Confirmation Modal */}
      <CommandConfirmationModal
        confirmation={pendingConfirmation}
        onConfirm={() => {
          if (pendingConfirmation) {
            const cmd = pendingConfirmation.actionArgs?.command || '';
            setPendingConfirmation(null);
            executeDispatch(cmd);
          }
        }}
        onCancel={() => {
          setPendingConfirmation(null);
          addMessage('system', 'Action cancelled by user.');
        }}
      />

      {/* Voice Match Training Calibration Modal */}
      <VoiceMatchModal
        isOpen={showVoiceMatchModal}
        onClose={() => setShowVoiceMatchModal(false)}
        assistantName={settings.assistantName}
        onSaveProfile={(score) => {
          setSettings(prev => ({ ...prev, voiceMatchScore: score, voiceMatchEnabled: true }));
          setShowVoiceMatchModal(false);
          addMessage('system', `Voice Match calibrated successfully! Enrolled voice profile score: ${score} Hz.`);
          setEmotion('happy');
        }}
      />

      {/* Screen Share Request Modal */}
      {showLiveScreenRequest && (
        <div className="fixed inset-0 z-50 bg-black/80 backdrop-blur-sm flex items-center justify-center p-4">
          <div className="bg-slate-900 border border-slate-700 w-full max-w-sm rounded-3xl p-6 text-center space-y-4 shadow-2xl">
            <div className="w-14 h-14 rounded-2xl bg-indigo-600/20 border border-indigo-500/40 text-indigo-400 flex items-center justify-center mx-auto">
              <Monitor className="w-7 h-7" />
            </div>
            <h3 className="font-bold text-sm text-white">Live Screen Share Request</h3>
            <p className="text-xs text-slate-300">
              {settings.assistantName} is requesting live screen sharing to assist with device automation and app interaction.
            </p>
            <div className="flex gap-2 justify-center pt-2">
              <button
                onClick={() => setShowLiveScreenRequest(false)}
                className="px-4 py-2 rounded-xl text-xs text-slate-400 hover:text-white"
              >
                Deny
              </button>
              <button
                onClick={async () => {
                  setShowLiveScreenRequest(false);
                  try {
                    await navigator.mediaDevices.getDisplayMedia({ video: true });
                    addMessage('system', 'Live screen sharing permission granted to assistant.');
                    setEmotion('focused');
                  } catch (e) {
                    addMessage('system', 'Screen sharing dismissed.');
                  }
                }}
                className="px-5 py-2 rounded-xl bg-indigo-600 hover:bg-indigo-500 text-white text-xs font-semibold shadow-lg shadow-indigo-600/30"
              >
                Allow Screen Share
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Settings Modal */}
      {showSettingsModal && (
        <SettingsModal
          settings={settings}
          onSave={(newSettings) => setSettings(newSettings)}
          onOpenVoiceMatchModal={() => {
            setShowSettingsModal(false);
            setShowVoiceMatchModal(true);
          }}
          onClose={() => setShowSettingsModal(false)}
        />
      )}

      {/* Install on Mobile Phone Modal */}
      <InstallAppModal
        isOpen={showInstallModal}
        onClose={() => setShowInstallModal(false)}
        deferredPrompt={deferredPrompt}
        onInstallSuccess={() => {
          addMessage('system', '🎉 Saara installed successfully on your phone!');
          setEmotion('happy');
        }}
      />
    </div>
  );
};

export default App;
