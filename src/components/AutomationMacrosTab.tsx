import React, { useState } from 'react';
import {
  Zap,
  Clock,
  Calendar,
  PhoneCall,
  MessageSquare,
  Smartphone,
  MapPin,
  Bell,
  Sliders,
  Eye,
  Camera,
  Play,
  Plus,
  Trash2,
  CheckCircle2,
  Activity,
  AlertCircle
} from 'lucide-react';
import { MacroRule, MacroTriggerType } from '../types';

interface AutomationMacrosTabProps {
  macros: MacroRule[];
  onAddMacro: (macro: MacroRule) => void;
  onToggleMacro: (id: string) => void;
  onDeleteMacro: (id: string) => void;
  onFireMacroTrigger: (macro: MacroRule) => void;
}

export const AutomationMacrosTab: React.FC<AutomationMacrosTabProps> = ({
  macros,
  onAddMacro,
  onToggleMacro,
  onDeleteMacro,
  onFireMacroTrigger
}) => {
  const [showAddModal, setShowAddModal] = useState(false);
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [triggerType, setTriggerType] = useState<MacroTriggerType>('time');
  const [triggerValue, setTriggerValue] = useState('5:00 PM');
  const [actionType, setActionType] = useState('speak_reminder');
  const [actionValue, setActionValue] = useState('Time to take your medicine');

  const getTriggerIcon = (type: MacroTriggerType) => {
    switch (type) {
      case 'time':
        return <Clock className="w-4 h-4 text-amber-400" />;
      case 'date':
        return <Calendar className="w-4 h-4 text-purple-400" />;
      case 'incoming_call':
        return <PhoneCall className="w-4 h-4 text-emerald-400" />;
      case 'message_receive':
        return <MessageSquare className="w-4 h-4 text-sky-400" />;
      case 'app_launch':
        return <Smartphone className="w-4 h-4 text-indigo-400" />;
      case 'location':
        return <MapPin className="w-4 h-4 text-rose-400" />;
      case 'notification':
        return <Bell className="w-4 h-4 text-yellow-400" />;
      case 'system_settings':
        return <Sliders className="w-4 h-4 text-teal-400" />;
      case 'photo_capture':
        return <Camera className="w-4 h-4 text-pink-400" />;
      case 'sensor':
        return <Activity className="w-4 h-4 text-orange-400" />;
      case 'screen_content':
        return <Eye className="w-4 h-4 text-cyan-400" />;
      default:
        return <Zap className="w-4 h-4 text-indigo-400" />;
    }
  };

  const handleCreateMacro = (e: React.FormEvent) => {
    e.preventDefault();
    if (!name.trim()) return;

    const newMacro: MacroRule = {
      id: `macro-${Date.now()}`,
      name: name.trim(),
      description: description.trim() || `${triggerType} -> ${actionType}`,
      triggerType,
      triggerValue: triggerValue.trim(),
      actionType,
      actionValue: actionValue.trim(),
      isEnabled: true,
      runCount: 0
    };

    onAddMacro(newMacro);
    setShowAddModal(false);
    setName('');
    setDescription('');
  };

  return (
    <div className="flex-1 flex flex-col h-full bg-slate-950 p-4 sm:p-6 overflow-y-auto space-y-6">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
        <div>
          <div className="flex items-center gap-2">
            <div className="w-8 h-8 rounded-xl bg-indigo-600/20 border border-indigo-500/40 flex items-center justify-center text-indigo-400">
              <Zap className="w-5 h-5" />
            </div>
            <h2 className="text-base font-bold text-white tracking-tight">MacroDroid Automation & Triggers</h2>
            <span className="text-[10px] font-mono px-2 py-0.5 rounded bg-emerald-500/10 text-emerald-400 border border-emerald-500/30">
              Zero-Confirmation Autonomy
            </span>
          </div>
          <p className="text-xs text-slate-400 mt-1">
            Configure autonomous background triggers. When any trigger fires, Saara wakes up automatically and executes the task without requesting confirmation.
          </p>
        </div>

        <button
          onClick={() => setShowAddModal(true)}
          className="px-4 py-2 bg-indigo-600 hover:bg-indigo-500 text-white rounded-xl text-xs font-semibold flex items-center gap-1.5 shadow-lg shadow-indigo-600/20 transition self-start sm:self-auto"
        >
          <Plus className="w-4 h-4" />
          Create Macro
        </button>
      </div>

      {/* Trigger Types Overview Bar */}
      <div className="grid grid-cols-2 sm:grid-cols-4 lg:grid-cols-6 gap-2">
        {[
          { label: 'Time / Alarm', icon: Clock, count: macros.filter(m => m.triggerType === 'time').length },
          { label: 'Date / Birthday', icon: Calendar, count: macros.filter(m => m.triggerType === 'date').length },
          { label: 'Incoming Call', icon: PhoneCall, count: macros.filter(m => m.triggerType === 'incoming_call').length },
          { label: 'Message / SMS', icon: MessageSquare, count: macros.filter(m => m.triggerType === 'message_receive').length },
          { label: 'Location / Geofence', icon: MapPin, count: macros.filter(m => m.triggerType === 'location').length },
          { label: 'App & System', icon: Smartphone, count: macros.filter(m => m.triggerType === 'app_launch' || m.triggerType === 'system_settings').length },
        ].map((t, idx) => {
          const Icon = t.icon;
          return (
            <div key={idx} className="p-3 bg-slate-900/60 border border-slate-800 rounded-xl flex items-center gap-2.5">
              <Icon className="w-4 h-4 text-indigo-400 shrink-0" />
              <div>
                <p className="text-[11px] font-semibold text-slate-200">{t.label}</p>
                <p className="text-[9px] text-slate-400">{t.count} active macros</p>
              </div>
            </div>
          );
        })}
      </div>

      {/* Macros List */}
      <div className="space-y-3">
        <h3 className="text-xs font-semibold uppercase tracking-wider text-slate-400">
          Configured Macro Rules ({macros.length})
        </h3>

        <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
          {macros.map((macro) => (
            <div
              key={macro.id}
              className={`p-4 rounded-2xl border transition-all duration-200 flex flex-col justify-between gap-3 ${
                macro.isEnabled
                  ? 'bg-slate-900/70 border-slate-800 hover:border-slate-700'
                  : 'bg-slate-950/40 border-slate-900 opacity-60'
              }`}
            >
              <div>
                <div className="flex items-start justify-between gap-2">
                  <div className="flex items-center gap-2">
                    <div className="p-2 rounded-xl bg-slate-800/80 border border-slate-700">
                      {getTriggerIcon(macro.triggerType)}
                    </div>
                    <div>
                      <h4 className="text-sm font-semibold text-white">{macro.name}</h4>
                      <span className="text-[10px] font-mono text-indigo-300">
                        Trigger: {macro.triggerType.toUpperCase()} ({macro.triggerValue})
                      </span>
                    </div>
                  </div>

                  {/* Enable / Disable switch */}
                  <input
                    type="checkbox"
                    checked={macro.isEnabled}
                    onChange={() => onToggleMacro(macro.id)}
                    className="w-4 h-4 accent-indigo-600 rounded cursor-pointer mt-1"
                  />
                </div>

                <p className="text-xs text-slate-300 mt-2.5 pl-1">{macro.description}</p>

                <div className="mt-2.5 p-2 bg-slate-950/60 rounded-xl border border-slate-800/80 text-[11px] text-slate-400 flex items-center justify-between">
                  <span>Action: <strong className="text-slate-200">{macro.actionType}</strong></span>
                  <span className="text-[10px] text-slate-500">
                    Fired {macro.runCount} times {macro.lastFired ? `• Last: ${macro.lastFired}` : ''}
                  </span>
                </div>
              </div>

              {/* Action Buttons */}
              <div className="flex items-center justify-between pt-2 border-t border-slate-800/60">
                <button
                  onClick={() => onFireMacroTrigger(macro)}
                  className="px-3 py-1.5 rounded-lg bg-emerald-600/20 hover:bg-emerald-600 text-emerald-300 hover:text-white border border-emerald-500/40 text-xs font-medium flex items-center gap-1.5 transition"
                  title="Simulate event and execute macro immediately"
                >
                  <Play className="w-3.5 h-3.5" />
                  Fire Trigger Now
                </button>

                <button
                  onClick={() => onDeleteMacro(macro.id)}
                  className="p-1.5 text-slate-500 hover:text-rose-400 transition"
                  title="Delete macro"
                >
                  <Trash2 className="w-4 h-4" />
                </button>
              </div>
            </div>
          ))}
        </div>
      </div>

      {/* Modal: Create Macro */}
      {showAddModal && (
        <div className="fixed inset-0 z-50 bg-black/80 backdrop-blur-sm flex items-center justify-center p-4">
          <div className="bg-slate-900 border border-slate-800 w-full max-w-lg rounded-2xl shadow-2xl p-5 space-y-4">
            <h3 className="font-bold text-sm text-white flex items-center gap-2">
              <Zap className="w-4 h-4 text-indigo-400" />
              Create MacroDroid Automation Trigger
            </h3>

            <form onSubmit={handleCreateMacro} className="space-y-3.5 text-xs">
              <div>
                <label className="text-slate-300 font-medium">Macro Name</label>
                <input
                  type="text"
                  placeholder="e.g. Daily Due Reminder to Clients"
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  className="w-full mt-1 bg-slate-950 border border-slate-800 rounded-lg px-3 py-2 text-white focus:outline-none focus:border-indigo-500"
                  required
                />
              </div>

              <div>
                <label className="text-slate-300 font-medium">Description</label>
                <input
                  type="text"
                  placeholder="What should Saara do when triggered?"
                  value={description}
                  onChange={(e) => setDescription(e.target.value)}
                  className="w-full mt-1 bg-slate-950 border border-slate-800 rounded-lg px-3 py-2 text-white focus:outline-none focus:border-indigo-500"
                />
              </div>

              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="text-slate-300 font-medium">Trigger Type</label>
                  <select
                    value={triggerType}
                    onChange={(e) => setTriggerType(e.target.value as MacroTriggerType)}
                    className="w-full mt-1 bg-slate-950 border border-slate-800 rounded-lg px-3 py-2 text-white focus:outline-none focus:border-indigo-500"
                  >
                    <option value="time">Time (Daily/Specific)</option>
                    <option value="date">Date (Birthday / Due date)</option>
                    <option value="incoming_call">Incoming Call</option>
                    <option value="message_receive">Message Receive (SMS/WhatsApp)</option>
                    <option value="app_launch">App Launch (e.g. Chrome, WhatsApp)</option>
                    <option value="location">Location (Arrive/Depart)</option>
                    <option value="notification">Notification Received</option>
                    <option value="system_settings">System Settings (Battery/WiFi)</option>
                    <option value="photo_capture">On Photo Capture</option>
                    <option value="sensor">Sensor / Accelerometer</option>
                    <option value="screen_content">Screen Content Detected</option>
                  </select>
                </div>

                <div>
                  <label className="text-slate-300 font-medium">Trigger Condition / Target</label>
                  <input
                    type="text"
                    placeholder="e.g. 5:00 PM or Mom"
                    value={triggerValue}
                    onChange={(e) => setTriggerValue(e.target.value)}
                    className="w-full mt-1 bg-slate-950 border border-slate-800 rounded-lg px-3 py-2 text-white focus:outline-none focus:border-indigo-500"
                    required
                  />
                </div>
              </div>

              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="text-slate-300 font-medium">Action Type</label>
                  <select
                    value={actionType}
                    onChange={(e) => setActionType(e.target.value)}
                    className="w-full mt-1 bg-slate-950 border border-slate-800 rounded-lg px-3 py-2 text-white focus:outline-none focus:border-indigo-500"
                  >
                    <option value="speak_reminder">Speak Voice Reminder (TTS)</option>
                    <option value="send_message">Send WhatsApp / SMS Message</option>
                    <option value="device_action">Device Hardware Control (Torch/Volume)</option>
                    <option value="take_photo">Capture Photo / Screenshot</option>
                    <option value="ai_task">Run Custom Gemini AI Task</option>
                  </select>
                </div>

                <div>
                  <label className="text-slate-300 font-medium">Action Content / Payload</label>
                  <input
                    type="text"
                    placeholder="e.g. Happy Birthday from our store!"
                    value={actionValue}
                    onChange={(e) => setActionValue(e.target.value)}
                    className="w-full mt-1 bg-slate-950 border border-slate-800 rounded-lg px-3 py-2 text-white focus:outline-none focus:border-indigo-500"
                  />
                </div>
              </div>

              <div className="pt-3 flex justify-end gap-2 border-t border-slate-800">
                <button
                  type="button"
                  onClick={() => setShowAddModal(false)}
                  className="px-4 py-2 rounded-lg text-slate-400 hover:text-white"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  className="px-4 py-2 rounded-lg bg-indigo-600 hover:bg-indigo-500 text-white font-medium"
                >
                  Save Macro
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
};
