import React, { useState } from 'react';
import { X, ShieldCheck, BookOpen, Clock, Trash2, Plus, CheckCircle, AlertCircle } from 'lucide-react';
import { ScheduledTask, KnowledgeItem } from '../types';

interface PrivacyNotebookModalProps {
  tasks: ScheduledTask[];
  knowledge: KnowledgeItem[];
  onAddTask: (desc: string, time: string) => void;
  onToggleTask: (id: string) => void;
  onDeleteTask: (id: string) => void;
  onAddKnowledge: (key: string, fact: string) => void;
  onDeleteKnowledge: (id: string) => void;
  onClose: () => void;
}

export const PrivacyNotebookModal: React.FC<PrivacyNotebookModalProps> = ({
  tasks,
  knowledge,
  onAddTask,
  onToggleTask,
  onDeleteTask,
  onAddKnowledge,
  onDeleteKnowledge,
  onClose
}) => {
  const [tab, setTab] = useState<'tasks' | 'knowledge' | 'privacy'>('tasks');
  const [newTaskDesc, setNewTaskDesc] = useState('');
  const [newTaskTime, setNewTaskTime] = useState('In 10 minutes');
  const [newKey, setNewKey] = useState('');
  const [newFact, setNewFact] = useState('');

  const handleCreateTask = (e: React.FormEvent) => {
    e.preventDefault();
    if (!newTaskDesc.trim()) return;
    onAddTask(newTaskDesc.trim(), newTaskTime.trim());
    setNewTaskDesc('');
  };

  const handleCreateKnowledge = (e: React.FormEvent) => {
    e.preventDefault();
    if (!newKey.trim() || !newFact.trim()) return;
    onAddKnowledge(newKey.trim(), newFact.trim());
    setNewKey('');
    setNewFact('');
  };

  return (
    <div className="fixed inset-0 z-50 bg-black/80 backdrop-blur-sm flex items-center justify-center p-4">
      <div className="bg-slate-900 border border-slate-800 w-full max-w-xl rounded-2xl shadow-2xl overflow-hidden flex flex-col max-h-[85vh]">
        {/* Header */}
        <div className="px-5 py-4 border-b border-slate-800 flex items-center justify-between bg-slate-950/60">
          <div className="flex items-center gap-2">
            <ShieldCheck className="w-5 h-5 text-emerald-400" />
            <div>
              <h3 className="font-semibold text-slate-100 text-sm">On-Device Local Vault</h3>
              <p className="text-[11px] text-slate-400">
                100% device-local storage. Never uploaded to cloud.
              </p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="p-1.5 rounded-lg text-slate-400 hover:text-slate-200 hover:bg-slate-800 transition"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* Tab switch */}
        <div className="flex border-b border-slate-800 bg-slate-950/30 px-5 pt-2">
          <button
            onClick={() => setTab('tasks')}
            className={`pb-2.5 px-3 text-xs font-medium border-b-2 transition flex items-center gap-1.5 ${
              tab === 'tasks'
                ? 'border-indigo-500 text-indigo-400'
                : 'border-transparent text-slate-400 hover:text-slate-300'
            }`}
          >
            <Clock className="w-3.5 h-3.5" />
            Scheduled Tasks ({tasks.length})
          </button>
          <button
            onClick={() => setTab('knowledge')}
            className={`pb-2.5 px-3 text-xs font-medium border-b-2 transition flex items-center gap-1.5 ${
              tab === 'knowledge'
                ? 'border-indigo-500 text-indigo-400'
                : 'border-transparent text-slate-400 hover:text-slate-300'
            }`}
          >
            <BookOpen className="w-3.5 h-3.5" />
            Learning Facts ({knowledge.length})
          </button>
          <button
            onClick={() => setTab('privacy')}
            className={`pb-2.5 px-3 text-xs font-medium border-b-2 transition flex items-center gap-1.5 ${
              tab === 'privacy'
                ? 'border-emerald-500 text-emerald-400'
                : 'border-transparent text-slate-400 hover:text-slate-300'
            }`}
          >
            <ShieldCheck className="w-3.5 h-3.5" />
            Privacy Shield
          </button>
        </div>

        {/* Content body */}
        <div className="p-5 overflow-y-auto flex-1 space-y-4">
          {tab === 'tasks' && (
            <div className="space-y-4">
              <form onSubmit={handleCreateTask} className="bg-slate-950/60 p-3 rounded-xl border border-slate-800 space-y-2.5">
                <p className="text-xs font-medium text-slate-300">Schedule New Local Task</p>
                <div className="flex gap-2">
                  <input
                    type="text"
                    placeholder="e.g. Call dentist or take medicine..."
                    value={newTaskDesc}
                    onChange={(e) => setNewTaskDesc(e.target.value)}
                    className="flex-1 bg-slate-900 border border-slate-700 rounded-lg px-3 py-1.5 text-xs text-slate-200 focus:outline-none focus:border-indigo-500"
                  />
                  <input
                    type="text"
                    placeholder="Time / Delay"
                    value={newTaskTime}
                    onChange={(e) => setNewTaskTime(e.target.value)}
                    className="w-32 bg-slate-900 border border-slate-700 rounded-lg px-3 py-1.5 text-xs text-slate-200 focus:outline-none focus:border-indigo-500"
                  />
                  <button
                    type="submit"
                    className="px-3 py-1.5 bg-indigo-600 hover:bg-indigo-500 text-white rounded-lg text-xs font-medium flex items-center gap-1 transition"
                  >
                    <Plus className="w-3.5 h-3.5" />
                    Add
                  </button>
                </div>
              </form>

              {tasks.length === 0 ? (
                <div className="text-center py-8 text-slate-500 text-xs">
                  No scheduled reminders or tasks yet. Saara can schedule them autonomously.
                </div>
              ) : (
                <div className="space-y-2">
                  {tasks.map((task) => (
                    <div
                      key={task.id}
                      className="flex items-center justify-between p-3 rounded-xl bg-slate-950/40 border border-slate-800/80 group"
                    >
                      <div className="flex items-center gap-3">
                        <button
                          onClick={() => onToggleTask(task.id)}
                          className={`w-5 h-5 rounded-md flex items-center justify-center border transition ${
                            task.completed
                              ? 'bg-emerald-600 border-emerald-500 text-white'
                              : 'border-slate-600 hover:border-slate-400 text-transparent'
                          }`}
                        >
                          <CheckCircle className="w-3.5 h-3.5" />
                        </button>
                        <div>
                          <p
                            className={`text-xs font-medium ${
                              task.completed ? 'line-through text-slate-500' : 'text-slate-200'
                            }`}
                          >
                            {task.description}
                          </p>
                          <p className="text-[10px] text-slate-400">
                            {task.timeOrDelay} • Created {task.createdAt}
                          </p>
                        </div>
                      </div>

                      <button
                        onClick={() => onDeleteTask(task.id)}
                        className="text-slate-600 hover:text-rose-400 opacity-0 group-hover:opacity-100 transition p-1"
                      >
                        <Trash2 className="w-3.5 h-3.5" />
                      </button>
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}

          {tab === 'knowledge' && (
            <div className="space-y-4">
              <form onSubmit={handleCreateKnowledge} className="bg-slate-950/60 p-3 rounded-xl border border-slate-800 space-y-2.5">
                <p className="text-xs font-medium text-slate-300">Save Local Learned Fact</p>
                <div className="grid grid-cols-3 gap-2">
                  <input
                    type="text"
                    placeholder="Key / Topic (e.g. coffee)"
                    value={newKey}
                    onChange={(e) => setNewKey(e.target.value)}
                    className="col-span-1 bg-slate-900 border border-slate-700 rounded-lg px-3 py-1.5 text-xs text-slate-200 focus:outline-none focus:border-indigo-500"
                  />
                  <input
                    type="text"
                    placeholder="Fact (e.g. loves oat milk cappuccino)"
                    value={newFact}
                    onChange={(e) => setNewFact(e.target.value)}
                    className="col-span-2 bg-slate-900 border border-slate-700 rounded-lg px-3 py-1.5 text-xs text-slate-200 focus:outline-none focus:border-indigo-500"
                  />
                </div>
                <div className="flex justify-end">
                  <button
                    type="submit"
                    className="px-3 py-1.5 bg-indigo-600 hover:bg-indigo-500 text-white rounded-lg text-xs font-medium flex items-center gap-1 transition"
                  >
                    <Plus className="w-3.5 h-3.5" />
                    Remember Fact
                  </button>
                </div>
              </form>

              {knowledge.length === 0 ? (
                <div className="text-center py-8 text-slate-500 text-xs">
                  No local facts saved yet. You can tell Saara to remember things like preferences.
                </div>
              ) : (
                <div className="space-y-2">
                  {knowledge.map((item) => (
                    <div
                      key={item.id}
                      className="p-3 rounded-xl bg-slate-950/40 border border-slate-800/80 flex items-start justify-between group"
                    >
                      <div>
                        <span className="inline-block px-2 py-0.5 rounded text-[10px] font-mono bg-indigo-500/10 text-indigo-300 border border-indigo-500/30 mb-1">
                          {item.key}
                        </span>
                        <p className="text-xs text-slate-200">{item.fact}</p>
                        <p className="text-[10px] text-slate-500 mt-1">Saved: {item.savedAt}</p>
                      </div>
                      <button
                        onClick={() => onDeleteKnowledge(item.id)}
                        className="text-slate-600 hover:text-rose-400 opacity-0 group-hover:opacity-100 transition p-1"
                      >
                        <Trash2 className="w-3.5 h-3.5" />
                      </button>
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}

          {tab === 'privacy' && (
            <div className="space-y-3.5 text-xs text-slate-300 leading-relaxed">
              <div className="p-3.5 rounded-xl bg-emerald-500/10 border border-emerald-500/30">
                <div className="flex items-center gap-2 font-semibold text-emerald-400 mb-1">
                  <ShieldCheck className="w-4 h-4" />
                  Saara Device-First Privacy Architecture
                </div>
                <p className="text-slate-300 text-xs">
                  Saara isolates your personal data from cloud AI models. Any voice command or prompt containing private keywords (contacts, passwords, SMS, bank credentials, addresses) is caught by the on-device guardrail and stored strictly in your local device vault.
                </p>
              </div>

              <div className="bg-slate-950/50 p-3.5 rounded-xl border border-slate-800 space-y-2">
                <h4 className="font-semibold text-slate-200 flex items-center gap-1.5">
                  <AlertCircle className="w-3.5 h-3.5 text-amber-400" />
                  Protected Local Vectors
                </h4>
                <ul className="list-disc list-inside space-y-1 text-slate-400 text-[11px]">
                  <li>Phone numbers & email addresses regex patterns</li>
                  <li>Device contacts, call logs & SMS message contents</li>
                  <li>Camera & Screen share frames (preview strictly on-device)</li>
                  <li>Local shop ledger and memory notebook</li>
                </ul>
              </div>

              <p className="text-[11px] text-slate-500 italic">
                Tip: Try saying "What is my bank account?" or "Remember my contact 555-1234" to see the local privacy shield in action!
              </p>
            </div>
          )}
        </div>
      </div>
    </div>
  );
};
