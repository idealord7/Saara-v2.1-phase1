import React, { useState } from 'react';
import {
  BookOpen,
  FolderPlus,
  Plus,
  Trash2,
  Edit2,
  FolderTree,
  MoveRight,
  Save,
  CheckCircle,
  Tag,
  Search
} from 'lucide-react';
import { KnowledgeCategory, KnowledgeItem } from '../types';

interface KnowledgeManagerTabProps {
  categories: KnowledgeCategory[];
  items: KnowledgeItem[];
  onAddCategory: (category: KnowledgeCategory) => void;
  onUpdateCategory: (category: KnowledgeCategory) => void;
  onDeleteCategory: (id: string) => void;
  onAddItem: (item: KnowledgeItem) => void;
  onUpdateItem: (item: KnowledgeItem) => void;
  onDeleteItem: (id: string) => void;
  onMoveItem: (itemId: string, newCategoryId: string) => void;
}

export const KnowledgeManagerTab: React.FC<KnowledgeManagerTabProps> = ({
  categories,
  items,
  onAddCategory,
  onUpdateCategory,
  onDeleteCategory,
  onAddItem,
  onUpdateItem,
  onDeleteItem,
  onMoveItem
}) => {
  const [selectedCatId, setSelectedCatId] = useState<string>('all');
  const [search, setSearch] = useState('');

  // Add Item state
  const [showAddItem, setShowAddItem] = useState(false);
  const [newKey, setNewKey] = useState('');
  const [newFact, setNewFact] = useState('');
  const [newCatId, setNewCatId] = useState(categories[0]?.id || 'cat-general');

  // Add Category state
  const [showAddCat, setShowAddCat] = useState(false);
  const [newCatName, setNewCatName] = useState('');
  const [newCatDesc, setNewCatDesc] = useState('');

  // Editing Item
  const [editingItemId, setEditingItemId] = useState<string | null>(null);
  const [editItemKey, setEditItemKey] = useState('');
  const [editItemFact, setEditItemFact] = useState('');
  const [editItemCatId, setEditItemCatId] = useState('');

  const filteredItems = items.filter(item => {
    const matchesCat = selectedCatId === 'all' || item.categoryId === selectedCatId;
    const matchesSearch =
      item.key.toLowerCase().includes(search.toLowerCase()) ||
      item.fact.toLowerCase().includes(search.toLowerCase());
    return matchesCat && matchesSearch;
  });

  const handleCreateCategory = (e: React.FormEvent) => {
    e.preventDefault();
    if (!newCatName.trim()) return;
    const cat: KnowledgeCategory = {
      id: `cat-${Date.now()}`,
      name: newCatName.trim(),
      description: newCatDesc.trim() || 'Custom Category'
    };
    onAddCategory(cat);
    setNewCatName('');
    setNewCatDesc('');
    setShowAddCat(false);
  };

  const handleCreateItem = (e: React.FormEvent) => {
    e.preventDefault();
    if (!newKey.trim() || !newFact.trim()) return;
    const item: KnowledgeItem = {
      id: `item-${Date.now()}`,
      categoryId: newCatId,
      key: newKey.trim(),
      fact: newFact.trim(),
      savedAt: new Date().toLocaleDateString()
    };
    onAddItem(item);
    setNewKey('');
    setNewFact('');
    setShowAddItem(false);
  };

  const startEditItem = (item: KnowledgeItem) => {
    setEditingItemId(item.id);
    setEditItemKey(item.key);
    setEditItemFact(item.fact);
    setEditItemCatId(item.categoryId);
  };

  const saveEditItem = (id: string) => {
    const existing = items.find(i => i.id === id);
    if (!existing) return;
    onUpdateItem({
      ...existing,
      key: editItemKey.trim(),
      fact: editItemFact.trim(),
      categoryId: editItemCatId
    });
    setEditingItemId(null);
  };

  return (
    <div className="flex-1 flex flex-col h-full bg-slate-950 p-4 sm:p-6 overflow-y-auto space-y-6">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
        <div>
          <div className="flex items-center gap-2">
            <div className="w-8 h-8 rounded-xl bg-purple-600/20 border border-purple-500/40 flex items-center justify-center text-purple-400">
              <BookOpen className="w-5 h-5" />
            </div>
            <h2 className="text-base font-bold text-white tracking-tight">Learning & Knowledge Vault</h2>
            <span className="text-[10px] font-mono px-2 py-0.5 rounded bg-purple-500/10 text-purple-400 border border-purple-500/30">
              Moveable & Editable
            </span>
          </div>
          <p className="text-xs text-slate-400 mt-1">
            Personal preferences, habits, and knowledge facts. Add categories, edit preferences, and move facts between categories.
          </p>
        </div>

        <div className="flex items-center gap-2 self-start sm:self-auto">
          <button
            onClick={() => setShowAddCat(prev => !prev)}
            className="px-3.5 py-2 bg-slate-800 hover:bg-slate-700 text-slate-200 rounded-xl text-xs font-semibold flex items-center gap-1.5 border border-slate-700 transition"
          >
            <FolderPlus className="w-4 h-4 text-purple-400" />
            Add Category
          </button>
          <button
            onClick={() => setShowAddItem(prev => !prev)}
            className="px-3.5 py-2 bg-indigo-600 hover:bg-indigo-500 text-white rounded-xl text-xs font-semibold flex items-center gap-1.5 shadow-lg shadow-indigo-600/20 transition"
          >
            <Plus className="w-4 h-4" />
            Add Preference
          </button>
        </div>
      </div>

      {/* Add Category Form */}
      {showAddCat && (
        <form onSubmit={handleCreateCategory} className="p-4 bg-slate-900 border border-slate-800 rounded-2xl space-y-3">
          <h4 className="text-xs font-semibold text-white">Create New Knowledge Category</h4>
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
            <input
              type="text"
              placeholder="Category Name (e.g. Work Preferences, Diet, VIP Contacts)"
              value={newCatName}
              onChange={(e) => setNewCatName(e.target.value)}
              className="bg-slate-950 border border-slate-800 rounded-lg px-3 py-2 text-xs text-white"
              required
            />
            <input
              type="text"
              placeholder="Category Description"
              value={newCatDesc}
              onChange={(e) => setNewCatDesc(e.target.value)}
              className="bg-slate-950 border border-slate-800 rounded-lg px-3 py-2 text-xs text-white"
            />
          </div>
          <div className="flex justify-end gap-2">
            <button
              type="button"
              onClick={() => setShowAddCat(false)}
              className="px-3 py-1.5 text-xs text-slate-400 hover:text-white"
            >
              Cancel
            </button>
            <button
              type="submit"
              className="px-4 py-1.5 bg-purple-600 hover:bg-purple-500 text-white rounded-lg text-xs font-medium"
            >
              Save Category
            </button>
          </div>
        </form>
      )}

      {/* Add Item Form */}
      {showAddItem && (
        <form onSubmit={handleCreateItem} className="p-4 bg-slate-900 border border-slate-800 rounded-2xl space-y-3">
          <h4 className="text-xs font-semibold text-white">Add Preference / Learned Fact</h4>
          <div className="grid grid-cols-1 sm:grid-cols-3 gap-2">
            <select
              value={newCatId}
              onChange={(e) => setNewCatId(e.target.value)}
              className="bg-slate-950 border border-slate-800 rounded-lg px-3 py-2 text-xs text-white"
            >
              {categories.map((c) => (
                <option key={c.id} value={c.id}>{c.name}</option>
              ))}
            </select>
            <input
              type="text"
              placeholder="Key / Label (e.g. coffee_time)"
              value={newKey}
              onChange={(e) => setNewKey(e.target.value)}
              className="bg-slate-950 border border-slate-800 rounded-lg px-3 py-2 text-xs text-white"
              required
            />
            <input
              type="text"
              placeholder="Preference value / statement"
              value={newFact}
              onChange={(e) => setNewFact(e.target.value)}
              className="bg-slate-950 border border-slate-800 rounded-lg px-3 py-2 text-xs text-white"
              required
            />
          </div>
          <div className="flex justify-end gap-2">
            <button
              type="button"
              onClick={() => setShowAddItem(false)}
              className="px-3 py-1.5 text-xs text-slate-400 hover:text-white"
            >
              Cancel
            </button>
            <button
              type="submit"
              className="px-4 py-1.5 bg-indigo-600 hover:bg-indigo-500 text-white rounded-lg text-xs font-medium"
            >
              Save Preference
            </button>
          </div>
        </form>
      )}

      {/* Filter and Category Pills */}
      <div className="flex flex-col sm:flex-row items-start sm:items-center justify-between gap-3">
        <div className="flex flex-wrap gap-1.5">
          <button
            onClick={() => setSelectedCatId('all')}
            className={`px-3 py-1.5 rounded-xl text-xs font-medium transition ${
              selectedCatId === 'all'
                ? 'bg-purple-600 text-white'
                : 'bg-slate-900 text-slate-400 hover:text-slate-200 border border-slate-800'
            }`}
          >
            All Categories ({items.length})
          </button>
          {categories.map((cat) => (
            <button
              key={cat.id}
              onClick={() => setSelectedCatId(cat.id)}
              className={`px-3 py-1.5 rounded-xl text-xs font-medium transition flex items-center gap-1.5 ${
                selectedCatId === cat.id
                  ? 'bg-purple-600 text-white'
                  : 'bg-slate-900 text-slate-400 hover:text-slate-200 border border-slate-800'
              }`}
            >
              <span>{cat.name}</span>
              <span className="text-[10px] text-purple-300">
                ({items.filter(i => i.categoryId === cat.id).length})
              </span>
            </button>
          ))}
        </div>

        <div className="relative w-full sm:w-64">
          <Search className="w-3.5 h-3.5 text-slate-400 absolute left-3 top-1/2 -translate-y-1/2" />
          <input
            type="text"
            placeholder="Search preferences..."
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="w-full bg-slate-900 border border-slate-800 rounded-xl pl-8 pr-3 py-1.5 text-xs text-white focus:outline-none focus:border-purple-500"
          />
        </div>
      </div>

      {/* Items Cards Grid */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
        {filteredItems.map((item) => {
          const currentCat = categories.find(c => c.id === item.categoryId);
          const isEditing = editingItemId === item.id;

          return (
            <div
              key={item.id}
              className="bg-slate-900/60 border border-slate-800 rounded-2xl p-4 flex flex-col justify-between gap-3"
            >
              {isEditing ? (
                <div className="space-y-2">
                  <div className="flex items-center gap-2">
                    <span className="text-xs text-slate-400">Category:</span>
                    <select
                      value={editItemCatId}
                      onChange={(e) => setEditItemCatId(e.target.value)}
                      className="bg-slate-950 border border-slate-700 rounded px-2 py-1 text-xs text-white flex-1"
                    >
                      {categories.map((c) => (
                        <option key={c.id} value={c.id}>{c.name}</option>
                      ))}
                    </select>
                  </div>
                  <input
                    type="text"
                    value={editItemKey}
                    onChange={(e) => setEditItemKey(e.target.value)}
                    className="w-full bg-slate-950 border border-slate-700 rounded px-2 py-1 text-xs text-white"
                  />
                  <textarea
                    value={editItemFact}
                    onChange={(e) => setEditItemFact(e.target.value)}
                    className="w-full bg-slate-950 border border-slate-700 rounded px-2 py-1 text-xs text-white h-16"
                  />
                  <div className="flex justify-end gap-2">
                    <button
                      onClick={() => setEditingItemId(null)}
                      className="px-2.5 py-1 text-xs text-slate-400"
                    >
                      Cancel
                    </button>
                    <button
                      onClick={() => saveEditItem(item.id)}
                      className="px-3 py-1 bg-emerald-600 rounded text-xs text-white font-medium flex items-center gap-1"
                    >
                      <Save className="w-3.5 h-3.5" /> Save
                    </button>
                  </div>
                </div>
              ) : (
                <div>
                  <div className="flex items-center justify-between">
                    <span className="px-2 py-0.5 rounded text-[10px] font-mono bg-purple-500/10 text-purple-300 border border-purple-500/20">
                      {currentCat?.name || 'General'}
                    </span>
                    <span className="text-[10px] text-slate-500 font-mono">Key: {item.key}</span>
                  </div>

                  <p className="text-xs font-medium text-slate-100 mt-2.5 leading-relaxed">{item.fact}</p>

                  {/* Move Category Selector */}
                  <div className="mt-3 pt-2 border-t border-slate-800/80 flex items-center justify-between text-xs">
                    <div className="flex items-center gap-1.5 text-slate-400">
                      <MoveRight className="w-3.5 h-3.5 text-purple-400" />
                      <span className="text-[11px]">Move to:</span>
                      <select
                        value={item.categoryId}
                        onChange={(e) => onMoveItem(item.id, e.target.value)}
                        className="bg-slate-950 border border-slate-800 rounded px-2 py-0.5 text-[11px] text-purple-300 focus:outline-none"
                      >
                        {categories.map((c) => (
                          <option key={c.id} value={c.id}>{c.name}</option>
                        ))}
                      </select>
                    </div>

                    <div className="flex items-center gap-1">
                      <button
                        onClick={() => startEditItem(item)}
                        className="p-1.5 text-slate-400 hover:text-white transition"
                        title="Edit Preference"
                      >
                        <Edit2 className="w-3.5 h-3.5" />
                      </button>
                      <button
                        onClick={() => onDeleteItem(item.id)}
                        className="p-1.5 text-slate-500 hover:text-rose-400 transition"
                        title="Delete"
                      >
                        <Trash2 className="w-3.5 h-3.5" />
                      </button>
                    </div>
                  </div>
                </div>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
};
