import React from 'react';
import {
  Flashlight,
  Volume2,
  VolumeX,
  Wifi,
  WifiOff,
  Bluetooth,
  Camera,
  Monitor,
  BatteryCharging,
  Battery,
  Clock,
  Sparkles
} from 'lucide-react';
import { DeviceState } from '../types';

interface DevicePanelProps {
  deviceState: DeviceState;
  onUpdateDevice: (partial: Partial<DeviceState>) => void;
  onOpenMedia: (mode: 'camera' | 'screen') => void;
  onOpenNotebook: () => void;
}

export const DevicePanel: React.FC<DevicePanelProps> = ({
  deviceState,
  onUpdateDevice,
  onOpenMedia,
  onOpenNotebook
}) => {
  return (
    <div className="bg-slate-900/70 border border-slate-800 rounded-2xl p-4 flex flex-col gap-4 backdrop-blur-sm">
      <div className="flex items-center justify-between">
        <h3 className="text-xs font-semibold uppercase tracking-wider text-slate-400 flex items-center gap-1.5">
          <Sparkles className="w-3.5 h-3.5 text-indigo-400" />
          Device Controls & Sensors
        </h3>
        <div className="flex items-center gap-1.5 text-xs text-slate-400">
          {deviceState.charging ? (
            <BatteryCharging className="w-4 h-4 text-emerald-400" />
          ) : (
            <Battery className="w-4 h-4 text-slate-400" />
          )}
          <span>{deviceState.battery}%</span>
        </div>
      </div>

      {/* Grid of quick action tiles */}
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-2.5">
        {/* Flashlight */}
        <button
          onClick={() => onUpdateDevice({ flashlight: !deviceState.flashlight })}
          className={`p-3 rounded-xl border flex flex-col items-center justify-center gap-1.5 transition ${
            deviceState.flashlight
              ? 'bg-amber-500/10 border-amber-500/40 text-amber-300 shadow-md shadow-amber-500/5'
              : 'bg-slate-950/40 border-slate-800 text-slate-400 hover:border-slate-700 hover:text-slate-200'
          }`}
        >
          <Flashlight className={`w-5 h-5 ${deviceState.flashlight ? 'text-amber-400' : ''}`} />
          <span className="text-[11px] font-medium">Torch: {deviceState.flashlight ? 'ON' : 'OFF'}</span>
        </button>

        {/* Mute */}
        <button
          onClick={() => onUpdateDevice({ muted: !deviceState.muted })}
          className={`p-3 rounded-xl border flex flex-col items-center justify-center gap-1.5 transition ${
            deviceState.muted
              ? 'bg-rose-500/10 border-rose-500/40 text-rose-300'
              : 'bg-slate-950/40 border-slate-800 text-slate-400 hover:border-slate-700 hover:text-slate-200'
          }`}
        >
          {deviceState.muted ? (
            <VolumeX className="w-5 h-5 text-rose-400" />
          ) : (
            <Volume2 className="w-5 h-5 text-slate-300" />
          )}
          <span className="text-[11px] font-medium">{deviceState.muted ? 'Muted' : 'Unmuted'}</span>
        </button>

        {/* Wi-Fi */}
        <button
          onClick={() => onUpdateDevice({ wifi: !deviceState.wifi })}
          className={`p-3 rounded-xl border flex flex-col items-center justify-center gap-1.5 transition ${
            deviceState.wifi
              ? 'bg-sky-500/10 border-sky-500/40 text-sky-300'
              : 'bg-slate-950/40 border-slate-800 text-slate-500 hover:border-slate-700'
          }`}
        >
          {deviceState.wifi ? <Wifi className="w-5 h-5 text-sky-400" /> : <WifiOff className="w-5 h-5" />}
          <span className="text-[11px] font-medium">Wi-Fi: {deviceState.wifi ? 'ON' : 'OFF'}</span>
        </button>

        {/* Bluetooth */}
        <button
          onClick={() => onUpdateDevice({ bluetooth: !deviceState.bluetooth })}
          className={`p-3 rounded-xl border flex flex-col items-center justify-center gap-1.5 transition ${
            deviceState.bluetooth
              ? 'bg-indigo-500/10 border-indigo-500/40 text-indigo-300'
              : 'bg-slate-950/40 border-slate-800 text-slate-500 hover:border-slate-700'
          }`}
        >
          <Bluetooth className={`w-5 h-5 ${deviceState.bluetooth ? 'text-indigo-400' : ''}`} />
          <span className="text-[11px] font-medium">Bluetooth</span>
        </button>
      </div>

      {/* Volume slider */}
      <div className="bg-slate-950/40 border border-slate-800/80 rounded-xl p-3 flex flex-col gap-1.5">
        <div className="flex items-center justify-between text-xs">
          <span className="text-slate-400 font-medium flex items-center gap-1.5">
            <Volume2 className="w-3.5 h-3.5 text-indigo-400" />
            Media Volume
          </span>
          <span className="text-slate-200 font-mono">{deviceState.volume}%</span>
        </div>
        <input
          type="range"
          min="0"
          max="100"
          value={deviceState.volume}
          onChange={(e) => onUpdateDevice({ volume: parseInt(e.target.value, 10) })}
          className="w-full accent-indigo-500 h-1.5 bg-slate-800 rounded-lg cursor-pointer"
        />
      </div>

      {/* Quick Launch Buttons: Camera / Screen / Notebook */}
      <div className="flex items-center gap-2 pt-1">
        <button
          onClick={() => onOpenMedia('camera')}
          className="flex-1 flex items-center justify-center gap-2 py-2 px-3 text-xs font-medium rounded-xl bg-slate-800 hover:bg-slate-700 border border-slate-700 text-slate-200 transition"
        >
          <Camera className="w-4 h-4 text-indigo-400" />
          Camera Feed
        </button>
        <button
          onClick={() => onOpenMedia('screen')}
          className="flex-1 flex items-center justify-center gap-2 py-2 px-3 text-xs font-medium rounded-xl bg-slate-800 hover:bg-slate-700 border border-slate-700 text-slate-200 transition"
        >
          <Monitor className="w-4 h-4 text-sky-400" />
          Screen Share
        </button>
        <button
          onClick={onOpenNotebook}
          className="flex-1 flex items-center justify-center gap-2 py-2 px-3 text-xs font-medium rounded-xl bg-slate-800 hover:bg-slate-700 border border-slate-700 text-slate-200 transition"
        >
          <Clock className="w-4 h-4 text-emerald-400" />
          Tasks & Vault
        </button>
      </div>

      {/* Active Timer Pill if any */}
      {deviceState.activeTimer && (
        <div className="bg-amber-500/10 border border-amber-500/30 rounded-xl p-2.5 flex items-center justify-between text-xs text-amber-200">
          <div className="flex items-center gap-2">
            <Clock className="w-4 h-4 text-amber-400 animate-spin" />
            <span>{deviceState.activeTimer.description || 'Active Timer'}:</span>
            <span className="font-mono font-bold text-amber-300">
              {Math.floor(deviceState.activeTimer.remaining / 60)}:
              {String(deviceState.activeTimer.remaining % 60).padStart(2, '0')}
            </span>
          </div>
          <button
            onClick={() => onUpdateDevice({ activeTimer: null })}
            className="text-amber-400 hover:text-amber-200 text-[11px] underline"
          >
            Cancel
          </button>
        </div>
      )}
    </div>
  );
};
