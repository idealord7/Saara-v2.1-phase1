import React from 'react';
import {
  X,
  Sliders,
  Shield,
  Key,
  Mic,
  Volume2,
  Sparkles,
  PhoneCall,
  CheckCircle,
  Zap,
  Activity
} from 'lucide-react';
import { AppSettings } from '../types';

interface SettingsModalProps {
  settings: AppSettings;
  onSave: (newSettings: AppSettings) => void;
  onOpenVoiceMatchModal: () => void;
  onClose: () => void;
}

export const SettingsModal: React.FC<SettingsModalProps> = ({
  settings,
  onSave,
  onOpenVoiceMatchModal,
  onClose
}) => {
  const [formData, setFormData] = React.useState<AppSettings>({ ...settings });

  const handleChange = (field: keyof AppSettings, value: any) => {
    setFormData(prev => ({ ...prev, [field]: value }));
  };

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    onSave(formData);
    onClose();
  };

  return (
    <div className="fixed inset-0 z-50 bg-black/80 backdrop-blur-sm flex items-center justify-center p-4">
      <div className="bg-slate-900 border border-slate-800 w-full max-w-lg rounded-3xl shadow-2xl overflow-hidden flex flex-col max-h-[90vh]">
        {/* Header */}
        <div className="px-5 py-4 border-b border-slate-800 flex items-center justify-between bg-slate-950/60 shrink-0">
          <div className="flex items-center gap-2">
            <Sliders className="w-5 h-5 text-indigo-400" />
            <h3 className="font-semibold text-slate-100 text-sm">Assistant & Voice Intelligence Settings</h3>
          </div>
          <button
            onClick={onClose}
            className="p-1.5 rounded-lg text-slate-400 hover:text-slate-200 hover:bg-slate-800 transition"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* Form */}
        <form onSubmit={handleSubmit} className="p-5 overflow-y-auto space-y-4 text-xs">
          {/* Assistant Name Section */}
          <div className="p-3.5 bg-slate-950/60 rounded-2xl border border-slate-800 space-y-2">
            <label className="text-slate-200 font-semibold flex items-center gap-1.5">
              <Sparkles className="w-4 h-4 text-indigo-400" />
              Assistant Name & Wake Word
            </label>
            <input
              type="text"
              value={formData.assistantName}
              onChange={(e) => handleChange('assistantName', e.target.value)}
              className="w-full bg-slate-900 border border-slate-700 rounded-lg px-3 py-2 text-white font-medium focus:outline-none focus:border-indigo-500"
              placeholder="e.g. Saara"
              required
            />
            <div className="flex items-center justify-between pt-1">
              <div>
                <p className="font-medium text-slate-300 text-[11px]">Only Process Voice After Name</p>
                <p className="text-[10px] text-slate-500">
                  Example: "{formData.assistantName || 'Saara'} make a call". Chatter without name is ignored.
                </p>
              </div>
              <input
                type="checkbox"
                checked={formData.wakeWordFilterEnabled}
                onChange={(e) => handleChange('wakeWordFilterEnabled', e.target.checked)}
                className="w-4 h-4 accent-indigo-600 rounded cursor-pointer"
              />
            </div>
          </div>

          {/* Hotwords Phrase Wakeup */}
          <div className="p-3.5 bg-slate-950/60 rounded-2xl border border-slate-800 space-y-2">
            <div className="flex items-center justify-between">
              <div>
                <p className="font-semibold text-slate-200 flex items-center gap-1.5">
                  <Mic className="w-4 h-4 text-emerald-400" />
                  Hotwords Phrase Wakeup
                </p>
                <p className="text-[10px] text-slate-500">
                  Wakes up assistant automatically when spoken phrase is heard (always-on mic)
                </p>
              </div>
              <input
                type="checkbox"
                checked={formData.hotwordsPhraseEnabled}
                onChange={(e) => handleChange('hotwordsPhraseEnabled', e.target.checked)}
                className="w-4 h-4 accent-indigo-600 rounded cursor-pointer"
              />
            </div>
            {formData.hotwordsPhraseEnabled && (
              <input
                type="text"
                value={formData.hotwordsPhrase}
                onChange={(e) => handleChange('hotwordsPhrase', e.target.value)}
                className="w-full bg-slate-900 border border-slate-700 rounded-lg px-3 py-1.5 text-white text-xs mt-1"
                placeholder="e.g. Hey Saara"
              />
            )}
          </div>

          {/* Voice Match Section */}
          <div className="p-3.5 bg-slate-950/60 rounded-2xl border border-slate-800 space-y-2.5">
            <div className="flex items-center justify-between">
              <div>
                <p className="font-semibold text-slate-200 flex items-center gap-1.5">
                  <Activity className="w-4 h-4 text-purple-400" />
                  Speaker Voice Match
                </p>
                <p className="text-[10px] text-slate-500">
                  Only responds to your verified voice profile. Other voices are rejected.
                </p>
              </div>
              <input
                type="checkbox"
                checked={formData.voiceMatchEnabled}
                onChange={(e) => handleChange('voiceMatchEnabled', e.target.checked)}
                className="w-4 h-4 accent-indigo-600 rounded cursor-pointer"
              />
            </div>
            <div className="flex items-center justify-between pt-1">
              <span className="text-[10px] text-slate-400">
                Profile: {formData.voiceMatchScore ? `Enrolled (${formData.voiceMatchScore} Hz timbre)` : 'Not yet enrolled'}
              </span>
              <button
                type="button"
                onClick={onOpenVoiceMatchModal}
                className="px-3 py-1 bg-purple-600/30 hover:bg-purple-600 border border-purple-500/40 rounded-lg text-[11px] text-purple-200 font-medium transition"
              >
                Train / Calibrate Voice
              </button>
            </div>
          </div>

          {/* Wakeup on Incoming Call */}
          <div className="p-3.5 bg-slate-950/60 rounded-2xl border border-slate-800 flex items-center justify-between">
            <div>
              <p className="font-semibold text-slate-200 flex items-center gap-1.5">
                <PhoneCall className="w-4 h-4 text-emerald-400" />
                Wakeup on Incoming Call
              </p>
              <p className="text-[10px] text-slate-500">
                Assistant wakes up on incoming call and asks to answer via voice ("Yes" or "No")
              </p>
            </div>
            <input
              type="checkbox"
              checked={formData.wakeupOnIncomingCall}
              onChange={(e) => handleChange('wakeupOnIncomingCall', e.target.checked)}
              className="w-4 h-4 accent-indigo-600 rounded cursor-pointer"
            />
          </div>

          {/* Confirmations & Automation Rules */}
          <div className="p-3.5 bg-slate-950/60 rounded-2xl border border-slate-800 space-y-3">
            <div className="flex items-center justify-between">
              <div>
                <p className="font-semibold text-slate-200">Require Confirmation for Commands</p>
                <p className="text-[10px] text-slate-500">
                  AI confirms before executing direct commands (Automation tasks run without confirmation)
                </p>
              </div>
              <input
                type="checkbox"
                checked={formData.requireCommandConfirmation}
                onChange={(e) => handleChange('requireCommandConfirmation', e.target.checked)}
                className="w-4 h-4 accent-indigo-600 rounded cursor-pointer"
              />
            </div>

            <div className="flex items-center justify-between pt-2 border-t border-slate-800">
              <div>
                <p className="font-semibold text-slate-200 flex items-center gap-1.5">
                  <Zap className="w-4 h-4 text-amber-400" />
                  Auto Lift Privacy for Complex Automation
                </p>
                <p className="text-[10px] text-slate-500">
                  Dynamically shares screen/camera with AI during multi-step browser & app tasks for peak performance
                </p>
              </div>
              <input
                type="checkbox"
                checked={formData.autoPrivacyForAutomation}
                onChange={(e) => handleChange('autoPrivacyForAutomation', e.target.checked)}
                className="w-4 h-4 accent-indigo-600 rounded cursor-pointer"
              />
            </div>
          </div>

          {/* Gemini API Key */}
          <div className="space-y-1.5">
            <label className="text-slate-300 font-medium flex items-center gap-1.5">
              <Key className="w-3.5 h-3.5 text-amber-400" />
              Gemini API Key
            </label>
            <input
              type="password"
              placeholder="Leave blank to use server environment key"
              value={formData.apiKey}
              onChange={(e) => handleChange('apiKey', e.target.value)}
              className="w-full bg-slate-950 border border-slate-800 rounded-lg px-3 py-2 text-slate-200 placeholder-slate-600 focus:outline-none focus:border-indigo-500 font-mono text-xs"
            />
          </div>

          {/* Model Selection */}
          <div className="space-y-1.5">
            <label className="text-slate-300 font-medium">Gemini Intelligence Model</label>
            <select
              value={formData.model}
              onChange={(e) => handleChange('model', e.target.value)}
              className="w-full bg-slate-950 border border-slate-800 rounded-lg px-3 py-2 text-slate-200 focus:outline-none focus:border-indigo-500"
            >
              <option value="gemini-2.5-flash">Gemini 2.5 Flash (Ultra Fast & Autonomous)</option>
              <option value="gemini-2.5-pro">Gemini 2.5 Pro (Deep Complex Reasoning)</option>
              <option value="gemini-2.0-flash">Gemini 2.0 Flash</option>
            </select>
          </div>

          <div className="pt-4 flex items-center justify-end gap-2 border-t border-slate-800">
            <button
              type="button"
              onClick={onClose}
              className="px-4 py-2 rounded-xl text-slate-400 hover:text-white"
            >
              Cancel
            </button>
            <button
              type="submit"
              className="px-5 py-2 rounded-xl bg-indigo-600 hover:bg-indigo-500 text-white font-medium shadow-lg shadow-indigo-600/30"
            >
              Save Settings
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};
