import React, { useState, useEffect } from 'react';
import {
  Smartphone,
  Globe,
  MessageSquare,
  Camera,
  Image as ImageIcon,
  Paperclip,
  Send,
  Search,
  CheckCircle,
  Share2,
  FileText,
  MousePointer,
  Play,
  Plus,
  Trash2,
  ShieldAlert,
  ArrowLeft,
  Sliders,
  ChevronRight
} from 'lucide-react';
import { TouchMacro, ScreenshotItem } from '../types';

interface MobileDeviceSimulatorProps {
  onCaptureScreenshot: (title: string) => void;
  onRequestLiveScreenShare: () => void;
  screenshots: ScreenshotItem[];
  touchMacros: TouchMacro[];
  onSaveTouchMacro: (macro: TouchMacro) => void;
  onDeleteTouchMacro: (id: string) => void;
  activeApp: string;
  setActiveApp: (app: string) => void;
  externalTouchPosition: { x: number; y: number; active: boolean } | null;
}

export const MobileDeviceSimulator: React.FC<MobileDeviceSimulatorProps> = ({
  onCaptureScreenshot,
  onRequestLiveScreenShare,
  screenshots,
  touchMacros,
  onSaveTouchMacro,
  onDeleteTouchMacro,
  activeApp,
  setActiveApp,
  externalTouchPosition
}) => {
  // Simulator internal states
  const [chromeUrl, setChromeUrl] = useState('https://www.google.com/search?q=latest+market+updates');
  const [chromeSearchQuery, setChromeSearchQuery] = useState('latest market prices');
  const [whatsappChat, setWhatsappChat] = useState<'Mom' | 'Customer #104' | 'Team'>('Customer #104');
  const [whatsappMessages, setWhatsappMessages] = useState<Array<{ sender: 'user' | 'saara' | 'them'; text: string; attachment?: string; time: string }>>([
    { sender: 'them', text: 'Hi, please send the latest product catalog or due reminder details.', time: '10:15 AM' },
    { sender: 'saara', text: 'Hello! Saara here. Here is your statement and verified catalog.', time: '10:16 AM' }
  ]);
  const [whatsappDraft, setWhatsappDraft] = useState('');
  const [showAttachMenu, setShowAttachMenu] = useState(false);
  const [attachedDocName, setAttachedDocName] = useState<string | null>(null);

  // Touch recording mode
  const [isRecordingMacro, setIsRecordingMacro] = useState(false);
  const [recordedSteps, setRecordedSteps] = useState<Array<{ x: number; y: number; label: string; action: 'tap'; delayMs: number }>>([]);
  const [macroName, setMacroName] = useState('Attach WhatsApp Document');

  // Interactive touch ripple state
  const [touchRipple, setTouchRipple] = useState<{ x: number; y: number; id: number } | null>(null);

  // Watch external touch position from Gemini autonomous tool calling
  useEffect(() => {
    if (externalTouchPosition?.active) {
      setTouchRipple({
        x: externalTouchPosition.x,
        y: externalTouchPosition.y,
        id: Date.now()
      });
      const timer = setTimeout(() => setTouchRipple(null), 1200);
      return () => clearTimeout(timer);
    }
  }, [externalTouchPosition]);

  const handleScreenClick = (e: React.MouseEvent<HTMLDivElement>) => {
    const rect = e.currentTarget.getBoundingClientRect();
    const x = Math.round(((e.clientX - rect.left) / rect.width) * 100);
    const y = Math.round(((e.clientY - rect.top) / rect.height) * 100);

    setTouchRipple({ x, y, id: Date.now() });
    setTimeout(() => setTouchRipple(null), 1000);

    if (isRecordingMacro) {
      const stepLabel = activeApp === 'whatsapp'
        ? (y > 80 && x < 25 ? 'Tap Paperclip Attach 📎' : y > 80 && x > 75 ? 'Tap Send Button' : `Tap at (${x}%, ${y}%)`)
        : `Tap screen (${x}%, ${y}%)`;

      setRecordedSteps(prev => [
        ...prev,
        { x, y, label: stepLabel, action: 'tap', delayMs: 600 }
      ]);
    }
  };

  const handleSendWhatsAppMessage = (textToSend?: string) => {
    const text = textToSend || whatsappDraft;
    if (!text.trim() && !attachedDocName) return;

    setWhatsappMessages(prev => [
      ...prev,
      {
        sender: 'saara',
        text: text.trim() || 'Attached document',
        attachment: attachedDocName || undefined,
        time: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
      }
    ]);
    setWhatsappDraft('');
    setAttachedDocName(null);
    setShowAttachMenu(false);
  };

  const handleSaveRecordedMacro = () => {
    if (recordedSteps.length === 0) return;
    const newMacro: TouchMacro = {
      id: `macro-${Date.now()}`,
      name: macroName.trim() || 'Recorded Gesture Macro',
      targetApp: activeApp,
      steps: recordedSteps.map(s => ({ ...s, delayMs: s.delayMs })),
      createdAt: new Date().toLocaleDateString()
    };
    onSaveTouchMacro(newMacro);
    setIsRecordingMacro(false);
    setRecordedSteps([]);
  };

  const executeTouchMacro = (macro: TouchMacro) => {
    setActiveApp(macro.targetApp);
    macro.steps.forEach((step, idx) => {
      setTimeout(() => {
        setTouchRipple({ x: step.x, y: step.y, id: Date.now() });
        if (macro.name.toLowerCase().includes('attach') && activeApp === 'whatsapp') {
          setShowAttachMenu(true);
          setAttachedDocName('Sales_Report_Nov2026.pdf');
        }
      }, (idx + 1) * 700);
    });
  };

  return (
    <div className="flex-1 flex flex-col xl:flex-row h-full overflow-hidden bg-slate-950 p-4 gap-4">
      {/* Left: Interactive Simulated Smartphone Device */}
      <div className="flex flex-col items-center justify-center shrink-0">
        <div className="relative w-[340px] h-[680px] bg-slate-900 border-8 border-slate-800 rounded-[44px] shadow-2xl flex flex-col overflow-hidden select-none">
          {/* Top Notch / Camera pill */}
          <div className="absolute top-2 left-1/2 -translate-x-1/2 w-28 h-4 bg-black rounded-full z-40 flex items-center justify-center">
            <div className="w-2.5 h-2.5 rounded-full bg-slate-950 border border-slate-700" />
          </div>

          {/* Android / iOS OS Status bar */}
          <div className="pt-2 px-6 pb-1 bg-slate-950 text-[10px] text-slate-300 flex items-center justify-between font-mono shrink-0 z-30">
            <span>{new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}</span>
            <div className="flex items-center gap-1.5">
              <span>5G</span>
              <span>100%</span>
            </div>
          </div>

          {/* Phone Screen Viewport */}
          <div
            onClick={handleScreenClick}
            className="relative flex-1 bg-slate-950 overflow-hidden flex flex-col cursor-crosshair"
          >
            {/* Visual Touch Ripple */}
            {touchRipple && (
              <div
                style={{ top: `${touchRipple.y}%`, left: `${touchRipple.x}%` }}
                className="absolute -translate-x-1/2 -translate-y-1/2 pointer-events-none z-50 flex items-center justify-center"
              >
                <div className="w-10 h-10 rounded-full bg-indigo-500/40 border-2 border-indigo-400 animate-ping" />
                <div className="absolute w-4 h-4 rounded-full bg-indigo-400 shadow-lg shadow-indigo-500 flex items-center justify-center text-[9px] text-black font-bold">
                  👆
                </div>
              </div>
            )}

            {/* SCREEN CONTENT: HOME SCREEN */}
            {activeApp === 'home' && (
              <div className="flex-1 p-5 flex flex-col justify-between bg-gradient-to-b from-indigo-950/40 to-slate-950">
                <div className="pt-8">
                  <div className="bg-slate-900/80 backdrop-blur-md rounded-2xl p-4 border border-slate-800 text-center">
                    <p className="text-xs text-indigo-400 font-semibold">Saara Device Assistant</p>
                    <p className="text-[11px] text-slate-400 mt-1">Autonomous Human-Like Control</p>
                  </div>
                </div>

                {/* App Grid */}
                <div className="grid grid-cols-4 gap-4 py-6">
                  <button
                    onClick={() => setActiveApp('chrome')}
                    className="flex flex-col items-center gap-1 group"
                  >
                    <div className="w-12 h-12 rounded-2xl bg-amber-500/20 border border-amber-500/40 flex items-center justify-center text-amber-400 group-hover:scale-105 transition">
                      <Globe className="w-6 h-6" />
                    </div>
                    <span className="text-[10px] text-slate-300 font-medium">Chrome</span>
                  </button>

                  <button
                    onClick={() => setActiveApp('whatsapp')}
                    className="flex flex-col items-center gap-1 group"
                  >
                    <div className="w-12 h-12 rounded-2xl bg-emerald-500/20 border border-emerald-500/40 flex items-center justify-center text-emerald-400 group-hover:scale-105 transition">
                      <MessageSquare className="w-6 h-6" />
                    </div>
                    <span className="text-[10px] text-slate-300 font-medium">WhatsApp</span>
                  </button>

                  <button
                    onClick={() => setActiveApp('gallery')}
                    className="flex flex-col items-center gap-1 group"
                  >
                    <div className="w-12 h-12 rounded-2xl bg-purple-500/20 border border-purple-500/40 flex items-center justify-center text-purple-400 group-hover:scale-105 transition">
                      <ImageIcon className="w-6 h-6" />
                    </div>
                    <span className="text-[10px] text-slate-300 font-medium">Gallery</span>
                  </button>

                  <button
                    onClick={() => {
                      onCaptureScreenshot('Home Screen Screenshot');
                    }}
                    className="flex flex-col items-center gap-1 group"
                  >
                    <div className="w-12 h-12 rounded-2xl bg-indigo-500/20 border border-indigo-500/40 flex items-center justify-center text-indigo-400 group-hover:scale-105 transition">
                      <Camera className="w-6 h-6" />
                    </div>
                    <span className="text-[10px] text-slate-300 font-medium">Snap Screen</span>
                  </button>
                </div>

                <div className="pb-3 text-center">
                  <p className="text-[10px] text-slate-500">Tap any app icon or teach Saara custom gestures</p>
                </div>
              </div>
            )}

            {/* SCREEN CONTENT: CHROME BROWSER */}
            {activeApp === 'chrome' && (
              <div className="flex-1 flex flex-col bg-slate-900 text-slate-200">
                {/* Browser URL Bar */}
                <div className="p-2.5 bg-slate-950 border-b border-slate-800 flex items-center gap-2">
                  <button onClick={() => setActiveApp('home')} className="p-1 text-slate-400 hover:text-white">
                    <ArrowLeft className="w-4 h-4" />
                  </button>
                  <div className="flex-1 bg-slate-900 rounded-lg px-2.5 py-1 text-xs text-slate-300 flex items-center gap-1.5 border border-slate-800">
                    <Globe className="w-3.5 h-3.5 text-amber-400" />
                    <input
                      type="text"
                      value={chromeUrl}
                      onChange={(e) => setChromeUrl(e.target.value)}
                      className="bg-transparent w-full focus:outline-none text-[11px] truncate"
                    />
                  </div>
                </div>

                {/* Simulated Webpage Body */}
                <div className="flex-1 p-4 overflow-y-auto space-y-3">
                  <div className="flex items-center gap-2 bg-slate-950 p-2.5 rounded-xl border border-slate-800">
                    <Search className="w-4 h-4 text-indigo-400" />
                    <input
                      type="text"
                      placeholder="Search Google..."
                      value={chromeSearchQuery}
                      onChange={(e) => setChromeSearchQuery(e.target.value)}
                      className="bg-transparent text-xs text-white focus:outline-none flex-1"
                    />
                  </div>

                  <div className="bg-slate-950/60 p-3 rounded-xl border border-slate-800 space-y-2">
                    <h4 className="text-xs font-semibold text-indigo-400">Search Results: {chromeSearchQuery}</h4>
                    <p className="text-[11px] text-slate-300">
                      Product index pricing updated. Gold Tier: $249, Silver Tier: $149, Starter Tier: $49.
                    </p>
                    <div className="flex items-center gap-2 pt-1">
                      <button
                        onClick={() => {
                          setWhatsappDraft('Extracted prices: Gold Tier $249, Silver Tier $149.');
                          setActiveApp('whatsapp');
                        }}
                        className="px-2.5 py-1 bg-indigo-600/80 hover:bg-indigo-500 rounded text-[10px] text-white flex items-center gap-1"
                      >
                        <Share2 className="w-3 h-3" />
                        Extract & Share to WhatsApp
                      </button>
                    </div>
                  </div>
                </div>
              </div>
            )}

            {/* SCREEN CONTENT: WHATSAPP MESSENGER */}
            {activeApp === 'whatsapp' && (
              <div className="flex-1 flex flex-col bg-slate-950 text-slate-200">
                {/* WhatsApp Chat Header */}
                <div className="px-3 py-2 bg-emerald-950/70 border-b border-emerald-800/40 flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <button onClick={() => setActiveApp('home')} className="p-1 text-slate-300 hover:text-white">
                      <ArrowLeft className="w-4 h-4" />
                    </button>
                    <div className="w-7 h-7 rounded-full bg-emerald-700 flex items-center justify-center font-bold text-xs text-white">
                      {whatsappChat[0]}
                    </div>
                    <div>
                      <p className="text-xs font-semibold text-white">{whatsappChat}</p>
                      <p className="text-[9px] text-emerald-400">Online • Encrypted</p>
                    </div>
                  </div>
                </div>

                {/* Messages stream */}
                <div className="flex-1 p-3 overflow-y-auto space-y-2.5 bg-slate-950/40">
                  {whatsappMessages.map((msg, i) => (
                    <div
                      key={i}
                      className={`flex flex-col ${msg.sender === 'saara' || msg.sender === 'user' ? 'items-end' : 'items-start'}`}
                    >
                      <div
                        className={`max-w-[85%] rounded-xl px-3 py-1.5 text-xs ${
                          msg.sender === 'saara'
                            ? 'bg-emerald-600 text-white rounded-br-none'
                            : 'bg-slate-800 text-slate-200 rounded-bl-none'
                        }`}
                      >
                        {msg.attachment && (
                          <div className="mb-1 p-1.5 rounded bg-emerald-700/80 flex items-center gap-1.5 text-[10px] font-mono border border-emerald-400/30">
                            <FileText className="w-3.5 h-3.5" />
                            <span className="truncate">{msg.attachment}</span>
                          </div>
                        )}
                        <p>{msg.text}</p>
                        <span className="text-[9px] text-emerald-200 block text-right mt-0.5">{msg.time}</span>
                      </div>
                    </div>
                  ))}
                </div>

                {/* WhatsApp Attach Menu Popup (Fix for attach document icon requirement) */}
                {showAttachMenu && (
                  <div className="p-3 bg-slate-900 border-t border-slate-800 grid grid-cols-3 gap-2 animate-in slide-in-from-bottom duration-200">
                    <button
                      onClick={() => {
                        setAttachedDocName('Invoice_Nov2026.pdf');
                        setShowAttachMenu(false);
                      }}
                      className="p-2 rounded-xl bg-purple-600/20 border border-purple-500/40 flex flex-col items-center gap-1 text-purple-300 hover:bg-purple-600/30"
                    >
                      <FileText className="w-5 h-5 text-purple-400" />
                      <span className="text-[9px] font-medium">Document</span>
                    </button>
                    <button
                      onClick={() => {
                        setAttachedDocName('Catalog_Photo.jpg');
                        setShowAttachMenu(false);
                      }}
                      className="p-2 rounded-xl bg-sky-600/20 border border-sky-500/40 flex flex-col items-center gap-1 text-sky-300 hover:bg-sky-600/30"
                    >
                      <ImageIcon className="w-5 h-5 text-sky-400" />
                      <span className="text-[9px] font-medium">Gallery</span>
                    </button>
                    <button
                      onClick={() => {
                        setAttachedDocName('Customer_Contact.vcf');
                        setShowAttachMenu(false);
                      }}
                      className="p-2 rounded-xl bg-indigo-600/20 border border-indigo-500/40 flex flex-col items-center gap-1 text-indigo-300 hover:bg-indigo-600/30"
                    >
                      <Smartphone className="w-5 h-5 text-indigo-400" />
                      <span className="text-[9px] font-medium">Contact</span>
                    </button>
                  </div>
                )}

                {/* WhatsApp Bottom Input bar with Paperclip 📎 icon */}
                <div className="p-2 bg-slate-900 border-t border-slate-800 flex items-center gap-1.5 shrink-0">
                  {/* WhatsApp document attach button (Specifically requested) */}
                  <button
                    onClick={() => setShowAttachMenu(prev => !prev)}
                    className={`p-2 rounded-full transition ${
                      showAttachMenu ? 'bg-indigo-600 text-white' : 'text-slate-400 hover:text-white hover:bg-slate-800'
                    }`}
                    title="Attach Document / Media"
                  >
                    <Paperclip className="w-4 h-4 rotate-45" />
                  </button>

                  <input
                    type="text"
                    placeholder={attachedDocName ? `Attached: ${attachedDocName}` : 'Type message...'}
                    value={whatsappDraft}
                    onChange={(e) => setWhatsappDraft(e.target.value)}
                    className="flex-1 bg-slate-950 border border-slate-800 rounded-full px-3 py-1.5 text-xs text-white focus:outline-none focus:border-emerald-500"
                  />

                  <button
                    onClick={() => handleSendWhatsAppMessage()}
                    className="p-2 rounded-full bg-emerald-600 hover:bg-emerald-500 text-white transition"
                    title="Send WhatsApp Message"
                  >
                    <Send className="w-3.5 h-3.5" />
                  </button>
                </div>
              </div>
            )}

            {/* SCREEN CONTENT: GALLERY / SCREENSHOTS */}
            {activeApp === 'gallery' && (
              <div className="flex-1 flex flex-col bg-slate-950 text-slate-200">
                <div className="p-3 bg-slate-900 border-b border-slate-800 flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <button onClick={() => setActiveApp('home')} className="p-1 text-slate-400 hover:text-white">
                      <ArrowLeft className="w-4 h-4" />
                    </button>
                    <h3 className="text-xs font-semibold">Device Screenshots & Gallery</h3>
                  </div>
                  <button
                    onClick={() => onCaptureScreenshot('Manual Screenshot')}
                    className="px-2 py-1 bg-indigo-600 rounded text-[10px] text-white"
                  >
                    Snap Now
                  </button>
                </div>

                <div className="flex-1 p-3 overflow-y-auto">
                  {screenshots.length === 0 ? (
                    <div className="text-center py-12 text-slate-500 text-xs">
                      No captured screenshots yet. Click "Snap Screen" or ask Saara to take a screenshot.
                    </div>
                  ) : (
                    <div className="grid grid-cols-2 gap-2">
                      {screenshots.map((s) => (
                        <div key={s.id} className="bg-slate-900 border border-slate-800 rounded-xl overflow-hidden p-1">
                          <img src={s.url} alt={s.title} className="w-full h-24 object-cover rounded-lg" />
                          <p className="text-[10px] font-medium text-slate-300 truncate mt-1">{s.title}</p>
                          <p className="text-[8px] text-slate-500">{s.timestamp}</p>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              </div>
            )}

            {/* Simulated Android Navigation Bar (Home, Back, Recents) */}
            <div className="h-6 bg-slate-950 flex items-center justify-around text-slate-600 shrink-0">
              <button onClick={() => setActiveApp('home')} className="w-4 h-4 rounded-full border border-slate-600 hover:border-slate-300" />
              <button onClick={() => setActiveApp('home')} className="w-4 h-1 rounded bg-slate-600 hover:bg-slate-300" />
            </div>
          </div>
        </div>
      </div>

      {/* Right: Human-like Gesture Learning & Macro Controller */}
      <div className="flex-1 flex flex-col bg-slate-900/60 border border-slate-800 rounded-2xl p-5 overflow-y-auto space-y-4">
        <div>
          <h2 className="text-sm font-bold text-white flex items-center gap-2">
            <MousePointer className="w-4 h-4 text-indigo-400" />
            Human-Like Device Interaction & Gesture Learning
          </h2>
          <p className="text-xs text-slate-400 mt-0.5">
            Teach Saara how to tap buttons, attach files in WhatsApp, search websites in Chrome, or request live screen sharing.
          </p>
        </div>

        {/* Quick Screen Share Request Action */}
        <div className="bg-indigo-950/40 border border-indigo-500/30 rounded-xl p-3.5 flex items-center justify-between">
          <div>
            <h4 className="text-xs font-semibold text-indigo-200">Live Screen Sharing Permission</h4>
            <p className="text-[11px] text-slate-400">
              When Saara encounters an external app or dialog, she can request live screen sharing with one tap allow.
            </p>
          </div>
          <button
            onClick={onRequestLiveScreenShare}
            className="px-3 py-1.5 bg-indigo-600 hover:bg-indigo-500 text-white rounded-lg text-xs font-medium shrink-0 transition"
          >
            Request Screen Share
          </button>
        </div>

        {/* Touch Macro Recording Station */}
        <div className="bg-slate-950/60 border border-slate-800 rounded-xl p-4 space-y-3">
          <div className="flex items-center justify-between">
            <h4 className="text-xs font-semibold text-slate-200 flex items-center gap-1.5">
              <span className={`w-2 h-2 rounded-full ${isRecordingMacro ? 'bg-rose-500 animate-ping' : 'bg-slate-500'}`} />
              {isRecordingMacro ? 'Recording Gestures (Click on the phone screen)...' : 'Teach Saara a Touch Action'}
            </h4>
            {!isRecordingMacro ? (
              <button
                onClick={() => {
                  setIsRecordingMacro(true);
                  setRecordedSteps([]);
                }}
                className="px-3 py-1 bg-indigo-600 hover:bg-indigo-500 rounded-lg text-xs text-white font-medium flex items-center gap-1"
              >
                <Plus className="w-3.5 h-3.5" />
                Record Gesture
              </button>
            ) : (
              <button
                onClick={handleSaveRecordedMacro}
                className="px-3 py-1 bg-emerald-600 hover:bg-emerald-500 rounded-lg text-xs text-white font-medium flex items-center gap-1"
              >
                <CheckCircle className="w-3.5 h-3.5" />
                Save Learned Gesture
              </button>
            )}
          </div>

          {isRecordingMacro && (
            <div className="space-y-2">
              <input
                type="text"
                placeholder="Gesture name e.g. Attach WhatsApp Document"
                value={macroName}
                onChange={(e) => setMacroName(e.target.value)}
                className="w-full bg-slate-900 border border-slate-700 rounded-lg px-3 py-1.5 text-xs text-white focus:outline-none"
              />
              <p className="text-[10px] text-amber-300">
                Now tap any element on the phone simulator screen on the left. Steps recorded: {recordedSteps.length}
              </p>
              <div className="flex flex-wrap gap-1">
                {recordedSteps.map((step, idx) => (
                  <span key={idx} className="px-2 py-0.5 rounded bg-slate-800 text-[10px] text-slate-300 border border-slate-700">
                    Step {idx + 1}: {step.label}
                  </span>
                ))}
              </div>
            </div>
          )}
        </div>

        {/* Saved Learned Touch Gestures */}
        <div className="space-y-2">
          <h4 className="text-xs font-semibold text-slate-300">Learned Touch Skills & Presets</h4>
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
            {/* Built-in Preset: Attach WhatsApp Document */}
            <div className="p-3 bg-slate-950/40 border border-slate-800 rounded-xl flex items-center justify-between group">
              <div>
                <p className="text-xs font-medium text-slate-200">WhatsApp: Attach Document 📎</p>
                <p className="text-[10px] text-slate-400">Taps paperclip → Selects document → Prepares file</p>
              </div>
              <button
                onClick={() => {
                  setActiveApp('whatsapp');
                  setTimeout(() => setShowAttachMenu(true), 300);
                  setTimeout(() => setAttachedDocName('Sales_Catalog_2026.pdf'), 800);
                }}
                className="p-1.5 rounded-lg bg-indigo-600/20 hover:bg-indigo-600 text-indigo-300 hover:text-white transition"
                title="Execute Gesture"
              >
                <Play className="w-3.5 h-3.5" />
              </button>
            </div>

            {/* Built-in Preset: Chrome Search Site & Extract */}
            <div className="p-3 bg-slate-950/40 border border-slate-800 rounded-xl flex items-center justify-between group">
              <div>
                <p className="text-xs font-medium text-slate-200">Chrome: Search & Extract Data 🌐</p>
                <p className="text-[10px] text-slate-400">Navigates to site → Extracts product price list</p>
              </div>
              <button
                onClick={() => {
                  setActiveApp('chrome');
                  setChromeSearchQuery('wholesale price sheet');
                }}
                className="p-1.5 rounded-lg bg-amber-600/20 hover:bg-amber-600 text-amber-300 hover:text-white transition"
                title="Execute Gesture"
              >
                <Play className="w-3.5 h-3.5" />
              </button>
            </div>

            {/* Custom User Learned Macros */}
            {touchMacros.map((macro) => (
              <div key={macro.id} className="p-3 bg-slate-950/40 border border-slate-800 rounded-xl flex items-center justify-between group">
                <div>
                  <p className="text-xs font-medium text-slate-200">{macro.name}</p>
                  <p className="text-[10px] text-slate-400">Target: {macro.targetApp} • {macro.steps.length} touch steps</p>
                </div>
                <div className="flex items-center gap-1">
                  <button
                    onClick={() => executeTouchMacro(macro)}
                    className="p-1.5 rounded-lg bg-emerald-600/20 hover:bg-emerald-600 text-emerald-300 hover:text-white transition"
                    title="Run Gesture"
                  >
                    <Play className="w-3.5 h-3.5" />
                  </button>
                  <button
                    onClick={() => onDeleteTouchMacro(macro.id)}
                    className="p-1.5 rounded-lg text-slate-500 hover:text-rose-400 transition"
                    title="Delete"
                  >
                    <Trash2 className="w-3.5 h-3.5" />
                  </button>
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
};
