import React from 'react';
import { AlertCircle, Check, X, ShieldAlert, Sparkles } from 'lucide-react';
import { PendingConfirmation } from '../types';

interface CommandConfirmationModalProps {
  confirmation: PendingConfirmation | null;
  onConfirm: () => void;
  onCancel: () => void;
}

export const CommandConfirmationModal: React.FC<CommandConfirmationModalProps> = ({
  confirmation,
  onConfirm,
  onCancel
}) => {
  if (!confirmation) return null;

  return (
    <div className="fixed inset-0 z-50 bg-black/80 backdrop-blur-sm flex items-center justify-center p-4 animate-in fade-in zoom-in-95 duration-150">
      <div className="bg-slate-900 border border-amber-500/50 w-full max-w-md rounded-2xl shadow-2xl p-5 space-y-4">
        <div className="flex items-center gap-2.5 text-amber-400">
          <div className="p-2 rounded-xl bg-amber-500/10 border border-amber-500/30">
            <ShieldAlert className="w-5 h-5 text-amber-400" />
          </div>
          <div>
            <h3 className="font-bold text-sm text-white">Action Confirmation Required</h3>
            <p className="text-[11px] text-amber-300">
              User confirmation requested before executing device action
            </p>
          </div>
        </div>

        <div className="p-3.5 rounded-xl bg-slate-950 border border-slate-800 space-y-2">
          <p className="text-xs font-semibold text-slate-200">{confirmation.title}</p>
          <p className="text-xs text-slate-300 leading-relaxed">{confirmation.details}</p>
          {confirmation.actionArgs && (
            <div className="p-2 rounded-lg bg-slate-900/80 text-[10px] font-mono text-indigo-300 border border-slate-800 truncate">
              Parameters: {JSON.stringify(confirmation.actionArgs)}
            </div>
          )}
        </div>

        <p className="text-[10px] text-slate-400 italic">
          Note: Macro automation tasks run automatically without confirmation. You can toggle command confirmation in Settings.
        </p>

        <div className="flex items-center justify-end gap-2 pt-2 border-t border-slate-800">
          <button
            onClick={onCancel}
            className="px-4 py-2 rounded-xl text-xs font-medium text-slate-300 hover:text-white hover:bg-slate-800 transition"
          >
            Cancel
          </button>
          <button
            onClick={onConfirm}
            className="px-4 py-2 rounded-xl text-xs font-medium bg-amber-600 hover:bg-amber-500 text-white flex items-center gap-1.5 shadow-lg shadow-amber-600/20 transition"
          >
            <Check className="w-3.5 h-3.5" />
            Confirm & Execute
          </button>
        </div>
      </div>
    </div>
  );
};
