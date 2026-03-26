import { useState, useEffect } from 'react';
import { api, type UserDto, type AuthorityDto } from '../lib/api';

type ModalMode = 'create' | 'edit' | null;

interface FormState {
  username: string;
  email: string;
  password: string;
  authorityIds: string[];
}

const emptyForm: FormState = { username: '', email: '', password: '', authorityIds: [] };

export default function UserManagement() {
  const [users, setUsers] = useState<UserDto[]>([]);
  const [authorities, setAuthorities] = useState<AuthorityDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [modalMode, setModalMode] = useState<ModalMode>(null);
  const [editingUser, setEditingUser] = useState<UserDto | null>(null);
  const [form, setForm] = useState<FormState>(emptyForm);
  const [saving, setSaving] = useState(false);
  const [deleteConfirm, setDeleteConfirm] = useState<UserDto | null>(null);

  useEffect(() => {
    loadAll();
  }, []);

  async function loadAll() {
    try {
      setError(null);
      const [usersData, authoritiesData] = await Promise.all([
        api.getUsers(),
        api.getAuthorities(),
      ]);
      setUsers(usersData);
      setAuthorities(authoritiesData);
    } catch (e: any) {
      setError(e.message ?? 'Betöltési hiba');
    } finally {
      setLoading(false);
    }
  }

  function openCreate() {
    setForm(emptyForm);
    setEditingUser(null);
    setModalMode('create');
  }

  function openEdit(user: UserDto) {
    setForm({ username: user.username, email: user.email ?? '', password: '', authorityIds: user.authorityIds ?? [] });
    setEditingUser(user);
    setModalMode('edit');
  }

  function closeModal() {
    setModalMode(null);
    setEditingUser(null);
    setForm(emptyForm);
  }

  function toggleAuthority(id: string) {
    setForm(f => ({
      ...f,
      authorityIds: f.authorityIds.includes(id)
        ? f.authorityIds.filter(a => a !== id)
        : [...f.authorityIds, id],
    }));
  }

  async function handleSave() {
    setSaving(true);
    try {
      if (modalMode === 'create') {
        if (!form.username.trim() || !form.password.trim()) {
          setError('Felhasználónév és jelszó megadása kötelező');
          return;
        }
        await api.createUser({ username: form.username, password: form.password, email: form.email, authorityIds: form.authorityIds });
      } else if (modalMode === 'edit' && editingUser) {
        await api.updateUser(editingUser.id, {
          email: form.email,
          password: form.password || undefined,
          authorityIds: form.authorityIds,
        });
      }
      closeModal();
      await loadAll();
    } catch (e: any) {
      setError(e.message ?? 'Mentési hiba');
    } finally {
      setSaving(false);
    }
  }

  async function handleDelete(user: UserDto) {
    try {
      await api.deleteUser(user.id);
      setDeleteConfirm(null);
      await loadAll();
    } catch (e: any) {
      setError(e.message ?? 'Törlési hiba');
    }
  }

  if (loading) {
    return (
      <div className="flex items-center justify-center h-48 text-gray-500">
        <div className="text-center">
          <div className="w-8 h-8 border-2 border-blue-500 border-t-transparent rounded-full animate-spin mx-auto mb-2" />
          <p className="text-sm">Betöltés...</p>
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-4">
      {error && (
        <div className="bg-red-50 border border-red-200 text-red-700 rounded-lg px-4 py-3 text-sm flex justify-between items-center">
          <span>{error}</span>
          <button onClick={() => setError(null)} className="ml-4 text-red-500 hover:text-red-700">✕</button>
        </div>
      )}

      <div className="flex justify-between items-center">
        <p className="text-sm text-gray-500">{users.length} felhasználó</p>
        <button
          onClick={openCreate}
          className="bg-blue-600 hover:bg-blue-700 text-white text-sm font-medium px-4 py-2 rounded-lg transition-colors"
        >
          + Új felhasználó
        </button>
      </div>

      <div className="bg-white rounded-xl border border-gray-200 overflow-hidden">
        <table className="w-full text-sm">
          <thead className="bg-gray-50 border-b border-gray-200">
            <tr>
              <th className="text-left px-4 py-3 font-medium text-gray-600">Felhasználónév</th>
              <th className="text-left px-4 py-3 font-medium text-gray-600">E-mail</th>
              <th className="text-left px-4 py-3 font-medium text-gray-600">Jogosultságok</th>
              <th className="px-4 py-3" />
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {users.length === 0 ? (
              <tr>
                <td colSpan={4} className="text-center py-8 text-gray-400">
                  Nincsenek felhasználók
                </td>
              </tr>
            ) : users.map(user => (
              <tr key={user.id} className="hover:bg-gray-50 transition-colors">
                <td className="px-4 py-3 font-medium text-gray-800">
                  <div className="flex items-center gap-2">
                    <span className="w-7 h-7 bg-blue-100 text-blue-700 rounded-full flex items-center justify-center text-xs font-bold shrink-0">
                      {user.username.charAt(0).toUpperCase()}
                    </span>
                    {user.username}
                  </div>
                </td>
                <td className="px-4 py-3 text-gray-500">{user.email || '—'}</td>
                <td className="px-4 py-3">
                  <div className="flex flex-wrap gap-1">
                    {user.authorities.map(a => (
                      <span
                        key={a}
                        className={`px-2 py-0.5 rounded-full text-xs font-medium ${
                          a === 'ROLE_ADMIN'
                            ? 'bg-purple-100 text-purple-700'
                            : 'bg-gray-100 text-gray-600'
                        }`}
                      >
                        {a}
                      </span>
                    ))}
                    {user.authorities.length === 0 && <span className="text-gray-400 text-xs">—</span>}
                  </div>
                </td>
                <td className="px-4 py-3">
                  <div className="flex gap-2 justify-end">
                    <button
                      onClick={() => openEdit(user)}
                      className="text-blue-600 hover:text-blue-800 text-xs font-medium px-2 py-1 rounded hover:bg-blue-50 transition-colors"
                    >
                      Szerkesztés
                    </button>
                    <button
                      onClick={() => setDeleteConfirm(user)}
                      className="text-red-500 hover:text-red-700 text-xs font-medium px-2 py-1 rounded hover:bg-red-50 transition-colors"
                    >
                      Törlés
                    </button>
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {/* Create/Edit Modal */}
      {modalMode && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4">
          <div className="bg-white rounded-xl shadow-xl w-full max-w-md">
            <div className="px-6 py-4 border-b border-gray-200">
              <h3 className="font-semibold text-gray-800">
                {modalMode === 'create' ? 'Új felhasználó létrehozása' : 'Felhasználó szerkesztése'}
              </h3>
            </div>
            <div className="px-6 py-4 space-y-4">
              {modalMode === 'create' && (
                <div>
                  <label className="block text-sm font-medium text-gray-700 mb-1">Felhasználónév *</label>
                  <input
                    type="text"
                    value={form.username}
                    onChange={e => setForm(f => ({ ...f, username: e.target.value }))}
                    className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
                    placeholder="pl. janos.kovacs"
                  />
                </div>
              )}
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">E-mail</label>
                <input
                  type="email"
                  value={form.email}
                  onChange={e => setForm(f => ({ ...f, email: e.target.value }))}
                  className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
                  placeholder="pl. janos@example.com"
                />
              </div>
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">
                  {modalMode === 'create' ? 'Jelszó *' : 'Új jelszó (üresen hagyva nem változik)'}
                </label>
                <input
                  type="password"
                  value={form.password}
                  onChange={e => setForm(f => ({ ...f, password: e.target.value }))}
                  className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
                  placeholder={modalMode === 'create' ? 'Jelszó' : 'Üresen hagyva nem változik'}
                />
              </div>
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-2">Jogosultságok</label>
                <div className="space-y-1 max-h-40 overflow-y-auto border border-gray-200 rounded-lg p-2">
                  {authorities.length === 0 && <p className="text-xs text-gray-400">Nincsenek elérhető jogosultságok</p>}
                  {authorities.map(a => (
                    <label key={a.id} className="flex items-center gap-2 cursor-pointer hover:bg-gray-50 px-2 py-1 rounded">
                      <input
                        type="checkbox"
                        checked={form.authorityIds.includes(a.id)}
                        onChange={() => toggleAuthority(a.id)}
                        className="rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                      />
                      <span className={`text-xs font-medium px-2 py-0.5 rounded-full ${
                        a.permission === 'ROLE_ADMIN'
                          ? 'bg-purple-100 text-purple-700'
                          : 'bg-gray-100 text-gray-600'
                      }`}>
                        {a.permission}
                      </span>
                    </label>
                  ))}
                </div>
              </div>
            </div>
            <div className="px-6 py-4 border-t border-gray-200 flex justify-end gap-2">
              <button
                onClick={closeModal}
                className="px-4 py-2 text-sm text-gray-600 hover:text-gray-800 border border-gray-300 rounded-lg hover:bg-gray-50 transition-colors"
              >
                Mégse
              </button>
              <button
                onClick={handleSave}
                disabled={saving}
                className="px-4 py-2 text-sm font-medium bg-blue-600 hover:bg-blue-700 text-white rounded-lg transition-colors disabled:opacity-50"
              >
                {saving ? 'Mentés...' : 'Mentés'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Delete confirmation */}
      {deleteConfirm && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4">
          <div className="bg-white rounded-xl shadow-xl w-full max-w-sm">
            <div className="px-6 py-4 border-b border-gray-200">
              <h3 className="font-semibold text-gray-800">Felhasználó törlése</h3>
            </div>
            <div className="px-6 py-4">
              <p className="text-sm text-gray-600">
                Biztosan törölni szeretnéd <strong>{deleteConfirm.username}</strong> felhasználót? Ez a művelet nem vonható vissza.
              </p>
            </div>
            <div className="px-6 py-4 border-t border-gray-200 flex justify-end gap-2">
              <button
                onClick={() => setDeleteConfirm(null)}
                className="px-4 py-2 text-sm text-gray-600 border border-gray-300 rounded-lg hover:bg-gray-50 transition-colors"
              >
                Mégse
              </button>
              <button
                onClick={() => handleDelete(deleteConfirm)}
                className="px-4 py-2 text-sm font-medium bg-red-600 hover:bg-red-700 text-white rounded-lg transition-colors"
              >
                Törlés
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
