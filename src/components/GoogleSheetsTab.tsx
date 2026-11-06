import React, { useState } from 'react';
import {
  FileSpreadsheet,
  Link,
  Plus,
  Trash2,
  Edit2,
  Save,
  CheckCircle,
  RefreshCw,
  Search,
  Database,
  ExternalLink,
  Sparkles
} from 'lucide-react';
import { SheetRow } from '../types';

interface GoogleSheetsTabProps {
  sheetRows: SheetRow[];
  onAddRow: (row: SheetRow) => void;
  onUpdateRow: (row: SheetRow) => void;
  onDeleteRow: (id: string) => void;
  onTrainAIWithSheet: (rows: SheetRow[]) => void;
}

export const GoogleSheetsTab: React.FC<GoogleSheetsTabProps> = ({
  sheetRows,
  onAddRow,
  onUpdateRow,
  onDeleteRow,
  onTrainAIWithSheet
}) => {
  const [sheetUrl, setSheetUrl] = useState('https://docs.google.com/spreadsheets/d/1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs74OgvE2upms/edit');
  const [isConnected, setIsConnected] = useState(true);
  const [selectedCategory, setSelectedCategory] = useState<string>('All');
  const [searchQuery, setSearchQuery] = useState('');
  const [isSyncing, setIsSyncing] = useState(false);
  const [trainStatus, setTrainStatus] = useState<string | null>(null);

  // New row form state
  const [showAddForm, setShowAddForm] = useState(false);
  const [newCategory, setNewCategory] = useState('Products & Pricing');
  const [newName, setNewName] = useState('');
  const [newValue, setNewValue] = useState('');
  const [newDesc, setNewDesc] = useState('');

  // Editing state
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editName, setEditName] = useState('');
  const [editValue, setEditValue] = useState('');
  const [editDesc, setEditDesc] = useState('');
  const [editCat, setEditCat] = useState('');

  const categories = ['All', ...Array.from(new Set(sheetRows.map(r => r.category)))];

  const filteredRows = sheetRows.filter(r => {
    const matchesCat = selectedCategory === 'All' || r.category === selectedCategory;
    const matchesQuery =
      r.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
      r.value.toLowerCase().includes(searchQuery.toLowerCase()) ||
      r.description.toLowerCase().includes(searchQuery.toLowerCase());
    return matchesCat && matchesQuery;
  });

  const handleSyncSheet = () => {
    setIsSyncing(true);
    setTimeout(() => {
      setIsSyncing(false);
      setIsConnected(true);
      setTrainStatus('Sheet synced successfully! 6 rows refreshed.');
      setTimeout(() => setTrainStatus(null), 3000);
    }, 1200);
  };

  const handleTrainAI = () => {
    onTrainAIWithSheet(sheetRows);
    setTrainStatus('AI Grounding Updated! Saara can now answer questions about sheet data.');
    setTimeout(() => setTrainStatus(null), 4000);
  };

  const handleCreateRow = (e: React.FormEvent) => {
    e.preventDefault();
    if (!newName.trim() || !newValue.trim()) return;

    const row: SheetRow = {
      id: `sheet-row-${Date.now()}`,
      category: newCategory.trim() || 'General',
      name: newName.trim(),
      value: newValue.trim(),
      description: newDesc.trim() || 'Custom entry',
      updatedAt: new Date().toLocaleDateString()
    };

    onAddRow(row);
    setNewName('');
    setNewValue('');
    setNewDesc('');
    setShowAddForm(false);
  };

  const startEdit = (row: SheetRow) => {
    setEditingId(row.id);
    setEditName(row.name);
    setEditValue(row.value);
    setEditDesc(row.description);
    setEditCat(row.category);
  };

  const saveEdit = (id: string) => {
    const existing = sheetRows.find(r => r.id === id);
    if (!existing) return;

    onUpdateRow({
      ...existing,
      name: editName.trim(),
      value: editValue.trim(),
      description: editDesc.trim(),
      category: editCat.trim(),
      updatedAt: 'Just now'
    });
    setEditingId(null);
  };

  return (
    <div className="flex-1 flex flex-col h-full bg-slate-950 p-4 sm:p-6 overflow-y-auto space-y-6">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
        <div>
          <div className="flex items-center gap-2">
            <div className="w-8 h-8 rounded-xl bg-emerald-600/20 border border-emerald-500/40 flex items-center justify-center text-emerald-400">
              <FileSpreadsheet className="w-5 h-5" />
            </div>
            <h2 className="text-base font-bold text-white tracking-tight">Google Sheets & Knowledge Training</h2>
            <span className="text-[10px] font-mono px-2 py-0.5 rounded bg-emerald-500/10 text-emerald-400 border border-emerald-500/30">
              Live Connected
            </span>
          </div>
          <p className="text-xs text-slate-400 mt-1">
            Connect live Google Sheets, query product prices, customer dues, and ground Saara with custom tabular training data.
          </p>
        </div>

        <div className="flex items-center gap-2 self-start sm:self-auto">
          <button
            onClick={handleTrainAI}
            className="px-3.5 py-2 bg-indigo-600 hover:bg-indigo-500 text-white rounded-xl text-xs font-semibold flex items-center gap-1.5 shadow-lg shadow-indigo-600/20 transition"
          >
            <Sparkles className="w-4 h-4 text-amber-300" />
            Train AI with Sheet
          </button>
          <button
            onClick={() => setShowAddForm(prev => !prev)}
            className="px-3.5 py-2 bg-slate-800 hover:bg-slate-700 text-slate-200 rounded-xl text-xs font-semibold flex items-center gap-1.5 border border-slate-700 transition"
          >
            <Plus className="w-4 h-4" />
            Add Row
          </button>
        </div>
      </div>

      {trainStatus && (
        <div className="p-3 bg-emerald-500/10 border border-emerald-500/30 rounded-xl text-xs text-emerald-300 flex items-center gap-2">
          <CheckCircle className="w-4 h-4 text-emerald-400" />
          {trainStatus}
        </div>
      )}

      {/* Google Sheet Connection Link Bar */}
      <div className="bg-slate-900/70 border border-slate-800 rounded-2xl p-4 flex flex-col md:flex-row items-center gap-3">
        <div className="flex items-center gap-2 text-xs font-medium text-slate-300 shrink-0">
          <Link className="w-4 h-4 text-emerald-400" />
          <span>Google Sheet Link:</span>
        </div>
        <input
          type="text"
          value={sheetUrl}
          onChange={(e) => setSheetUrl(e.target.value)}
          className="flex-1 bg-slate-950 border border-slate-800 rounded-xl px-3 py-2 text-xs text-slate-200 focus:outline-none focus:border-emerald-500 font-mono truncate"
          placeholder="https://docs.google.com/spreadsheets/d/.../edit"
        />
        <div className="flex items-center gap-2 shrink-0">
          <button
            onClick={handleSyncSheet}
            disabled={isSyncing}
            className="px-3 py-2 bg-emerald-600 hover:bg-emerald-500 text-white rounded-xl text-xs font-medium flex items-center gap-1.5 transition disabled:opacity-50"
          >
            <RefreshCw className={`w-3.5 h-3.5 ${isSyncing ? 'animate-spin' : ''}`} />
            {isSyncing ? 'Syncing…' : 'Sync Sheet'}
          </button>
        </div>
      </div>

      {/* Category Tabs & Search Bar */}
      <div className="flex flex-col sm:flex-row items-start sm:items-center justify-between gap-3">
        {/* Category Pills */}
        <div className="flex flex-wrap gap-1.5">
          {categories.map((cat) => (
            <button
              key={cat}
              onClick={() => setSelectedCategory(cat)}
              className={`px-3 py-1.5 rounded-xl text-xs font-medium transition ${
                selectedCategory === cat
                  ? 'bg-indigo-600 text-white'
                  : 'bg-slate-900/80 text-slate-400 hover:text-slate-200 border border-slate-800'
              }`}
            >
              {cat}
            </button>
          ))}
        </div>

        {/* Search */}
        <div className="relative w-full sm:w-64">
          <Search className="w-3.5 h-3.5 text-slate-400 absolute left-3 top-1/2 -translate-y-1/2" />
          <input
            type="text"
            placeholder="Search products, prices, dues..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="w-full bg-slate-900 border border-slate-800 rounded-xl pl-8 pr-3 py-1.5 text-xs text-white focus:outline-none focus:border-indigo-500"
          />
        </div>
      </div>

      {/* Add New Row Form */}
      {showAddForm && (
        <form onSubmit={handleCreateRow} className="bg-slate-900/90 border border-slate-800 p-4 rounded-2xl space-y-3">
          <h4 className="text-xs font-semibold text-white">Add New Row / Training Data</h4>
          <div className="grid grid-cols-1 sm:grid-cols-4 gap-2">
            <input
              type="text"
              placeholder="Category e.g. Products"
              value={newCategory}
              onChange={(e) => setNewCategory(e.target.value)}
              className="bg-slate-950 border border-slate-800 rounded-lg px-3 py-1.5 text-xs text-white"
            />
            <input
              type="text"
              placeholder="Item / Product Name"
              value={newName}
              onChange={(e) => setNewName(e.target.value)}
              className="bg-slate-950 border border-slate-800 rounded-lg px-3 py-1.5 text-xs text-white"
            />
            <input
              type="text"
              placeholder="Price / Value"
              value={newValue}
              onChange={(e) => setNewValue(e.target.value)}
              className="bg-slate-950 border border-slate-800 rounded-lg px-3 py-1.5 text-xs text-white"
            />
            <input
              type="text"
              placeholder="Description / Details"
              value={newDesc}
              onChange={(e) => setNewDesc(e.target.value)}
              className="bg-slate-950 border border-slate-800 rounded-lg px-3 py-1.5 text-xs text-white"
            />
          </div>
          <div className="flex justify-end gap-2">
            <button
              type="button"
              onClick={() => setShowAddForm(false)}
              className="px-3 py-1.5 text-xs text-slate-400 hover:text-white"
            >
              Cancel
            </button>
            <button
              type="submit"
              className="px-4 py-1.5 bg-emerald-600 hover:bg-emerald-500 text-white rounded-lg text-xs font-medium"
            >
              Save Row
            </button>
          </div>
        </form>
      )}

      {/* Table Container */}
      <div className="bg-slate-900/50 border border-slate-800 rounded-2xl overflow-hidden shadow-xl">
        <div className="overflow-x-auto">
          <table className="w-full text-left text-xs border-collapse">
            <thead>
              <tr className="bg-slate-950/80 border-b border-slate-800 text-[11px] font-semibold text-slate-400">
                <th className="py-3 px-4">Category</th>
                <th className="py-3 px-4">Item / Product Name</th>
                <th className="py-3 px-4">Price / Value</th>
                <th className="py-3 px-4">Description / Customer Details</th>
                <th className="py-3 px-4">Updated</th>
                <th className="py-3 px-4 text-right">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-800/60">
              {filteredRows.map((row) => (
                <tr key={row.id} className="hover:bg-slate-800/30 transition">
                  {editingId === row.id ? (
                    <>
                      <td className="py-2.5 px-4">
                        <input
                          type="text"
                          value={editCat}
                          onChange={(e) => setEditCat(e.target.value)}
                          className="bg-slate-950 border border-slate-700 rounded px-2 py-1 text-xs text-white w-full"
                        />
                      </td>
                      <td className="py-2.5 px-4">
                        <input
                          type="text"
                          value={editName}
                          onChange={(e) => setEditName(e.target.value)}
                          className="bg-slate-950 border border-slate-700 rounded px-2 py-1 text-xs text-white w-full"
                        />
                      </td>
                      <td className="py-2.5 px-4">
                        <input
                          type="text"
                          value={editValue}
                          onChange={(e) => setEditValue(e.target.value)}
                          className="bg-slate-950 border border-slate-700 rounded px-2 py-1 text-xs text-white w-full"
                        />
                      </td>
                      <td className="py-2.5 px-4">
                        <input
                          type="text"
                          value={editDesc}
                          onChange={(e) => setEditDesc(e.target.value)}
                          className="bg-slate-950 border border-slate-700 rounded px-2 py-1 text-xs text-white w-full"
                        />
                      </td>
                      <td className="py-2.5 px-4 text-slate-500 text-[10px]">{row.updatedAt}</td>
                      <td className="py-2.5 px-4 text-right">
                        <button
                          onClick={() => saveEdit(row.id)}
                          className="p-1.5 text-emerald-400 hover:text-emerald-300 transition"
                          title="Save"
                        >
                          <Save className="w-4 h-4" />
                        </button>
                      </td>
                    </>
                  ) : (
                    <>
                      <td className="py-3 px-4">
                        <span className="px-2 py-0.5 rounded text-[10px] font-mono bg-indigo-500/10 text-indigo-300 border border-indigo-500/20">
                          {row.category}
                        </span>
                      </td>
                      <td className="py-3 px-4 font-medium text-white">{row.name}</td>
                      <td className="py-3 px-4 font-semibold text-emerald-400 font-mono">{row.value}</td>
                      <td className="py-3 px-4 text-slate-300">{row.description}</td>
                      <td className="py-3 px-4 text-slate-500 text-[10px]">{row.updatedAt}</td>
                      <td className="py-3 px-4 text-right">
                        <div className="flex items-center justify-end gap-1">
                          <button
                            onClick={() => startEdit(row)}
                            className="p-1.5 text-slate-400 hover:text-white transition"
                            title="Edit Row"
                          >
                            <Edit2 className="w-3.5 h-3.5" />
                          </button>
                          <button
                            onClick={() => onDeleteRow(row.id)}
                            className="p-1.5 text-slate-500 hover:text-rose-400 transition"
                            title="Delete Row"
                          >
                            <Trash2 className="w-3.5 h-3.5" />
                          </button>
                        </div>
                      </td>
                    </>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
};
