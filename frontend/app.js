// SGMIS Web Management & Command Portal
// Authoritative client for Administrators and Supervisors

const DEFAULT_API_URL = window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1' 
    ? 'http://localhost:8000' 
    : window.location.origin;

let API_BASE = localStorage.getItem('sgmis_api_url') || DEFAULT_API_URL;
let token = localStorage.getItem('sgmis_access_token');
let currentUser = JSON.parse(localStorage.getItem('sgmis_user') || 'null');
let currentTab = 'dashboard';
let dashboardStats = null;

async function apiRequest(endpoint, method = 'GET', body = null) {
  const headers = { 'Content-Type': 'application/json' };
  if (token) {
    headers['Authorization'] = `Bearer ${token}`;
  }
  const url = `${API_BASE}${endpoint}`;
  try {
    const res = await fetch(url, {
      method,
      headers,
      body: body ? JSON.stringify(body) : null,
    });
    if (res.status === 401 && token) {
      // Token expired or invalid
      logout();
      throw new Error('Session expired. Please log in again.');
    }
    const data = await res.json().catch(() => ({}));
    if (!res.ok) {
      throw new Error(data.detail || data.message || `API Error: ${res.status}`);
    }
    return data;
  } catch (err) {
    console.error('API Request error:', err);
    throw err;
  }
}

function saveAuth(access, refresh, user) {
  token = access;
  currentUser = user;
  localStorage.setItem('sgmis_access_token', access);
  localStorage.setItem('sgmis_refresh_token', refresh);
  localStorage.setItem('sgmis_user', JSON.stringify(user));
}

function logout() {
  token = null;
  currentUser = null;
  localStorage.removeItem('sgmis_access_token');
  localStorage.removeItem('sgmis_refresh_token');
  localStorage.removeItem('sgmis_user');
  render();
}

function render() {
  const app = document.getElementById('app');
  if (!token || !currentUser) {
    renderLogin(app);
  } else {
    renderPortal(app);
  }
}

function renderLogin(container) {
  container.innerHTML = `
    <div class="min-h-screen flex items-center justify-center p-4 bg-navy-950">
      <div class="max-w-md w-full bg-navy-900 border border-navy-700 rounded-2xl p-8 shadow-2xl">
        <div class="flex items-center space-x-3 mb-6">
          <div class="w-12 h-12 rounded-xl bg-gold-500 flex items-center justify-center text-navy-900 font-bold text-2xl shadow-lg">
            🛡️
          </div>
          <div>
            <h1 class="text-2xl font-bold text-white tracking-wide">SGMIS COMMAND</h1>
            <p class="text-xs text-gold-500 font-semibold uppercase tracking-wider">Security Guard Management Information System</p>
          </div>
        </div>

        <div id="login-error" class="hidden mb-4 p-3 bg-red-900/40 border border-red-500/50 rounded-lg text-sm text-red-200"></div>

        <form id="login-form" class="space-y-5">
          <div>
            <label class="block text-xs font-semibold uppercase tracking-wider text-gray-400 mb-2">Username or Employee ID</label>
            <input type="text" id="identifier" required placeholder="e.g. simeonmbwani or SEC-1001" 
                   class="w-full px-4 py-3 bg-navy-800 border border-navy-700 rounded-lg text-white placeholder-gray-500 focus:outline-none focus:border-gold-500 transition-colors">
          </div>

          <div>
            <label class="block text-xs font-semibold uppercase tracking-wider text-gray-400 mb-2">Security Password</label>
            <input type="password" id="password" required placeholder="••••••••" 
                   class="w-full px-4 py-3 bg-navy-800 border border-navy-700 rounded-lg text-white placeholder-gray-500 focus:outline-none focus:border-gold-500 transition-colors">
          </div>

          <div class="pt-2">
            <button type="submit" id="login-btn"
                    class="w-full py-3.5 px-4 bg-gold-500 hover:bg-gold-600 text-navy-950 font-bold rounded-lg transition-all shadow-md hover:shadow-gold-500/20 active:scale-[0.99]">
              Authenticate to System
            </button>
          </div>
        </form>

        <div class="mt-8 pt-6 border-t border-navy-800 text-center">
          <p class="text-xs text-gray-500">API Endpoint: <span class="text-gray-400 font-mono">${API_BASE}</span></p>
          <button onclick="configureApiUrl()" class="mt-2 text-xs text-gold-500 hover:underline">Change Backend URL</button>
        </div>
      </div>
    </div>
  `;

  document.getElementById('login-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const btn = document.getElementById('login-btn');
    const errBox = document.getElementById('login-error');
    btn.disabled = true;
    btn.innerText = 'Authenticating...';
    errBox.classList.add('hidden');

    try {
      const res = await apiRequest('/auth/login/', 'POST', {
        identifier: document.getElementById('identifier').value.trim(),
        password: document.getElementById('password').value,
      });
      saveAuth(res.access, res.refresh, res.user);
      render();
    } catch (err) {
      errBox.innerText = err.message;
      errBox.classList.remove('hidden');
      btn.disabled = false;
      btn.innerText = 'Authenticate to System';
    }
  });
}

function configureApiUrl() {
  const current = API_BASE;
  const next = prompt('Enter Django API Base URL (e.g. http://localhost:8000 or https://your-cloud-run.app):', current);
  if (next && next.trim()) {
    API_BASE = next.trim().replace(/\/$/, '');
    localStorage.setItem('sgmis_api_url', API_BASE);
    render();
  }
}

function renderPortal(container) {
  container.innerHTML = `
    <div class="min-h-screen flex flex-col md:flex-row bg-navy-950">
      <!-- Sidebar Navigation -->
      <aside class="w-full md:w-64 bg-navy-900 border-r border-navy-800 flex flex-col">
        <div class="p-6 border-b border-navy-800 flex items-center justify-between">
          <div class="flex items-center space-x-3">
            <span class="text-2xl">🛡️</span>
            <div>
              <h2 class="text-lg font-bold text-white">SGMIS</h2>
              <span class="text-[10px] px-2 py-0.5 rounded bg-gold-500/20 text-gold-500 font-bold uppercase tracking-wider">${currentUser.role}</span>
            </div>
          </div>
        </div>

        <nav class="p-4 space-y-1 flex-1 overflow-y-auto">
          ${navItem('dashboard', '📊 Operations Dashboard')}
          ${navItem('users', '👥 Personnel & Guards')}
          ${navItem('stations', '🏢 Deployment Stations')}
          ${navItem('pairs', '🤝 Guard Pairs')}
          ${navItem('roster', '📅 Duty Roster Engine')}
          ${navItem('attendance', '⏱️ Live Attendance')}
          ${navItem('handovers', '🔄 Shift Handovers')}
          ${navItem('incidents', '🚨 Incident Command')}
          ${navItem('ob', '📖 Occurrence Book')}
          ${navItem('patrols', '🚶 Patrols & Checkpoints')}
          ${navItem('leave', '📝 Leave Administration')}
        </nav>

        <div class="p-4 border-t border-navy-800 bg-navy-900/50">
          <div class="flex items-center justify-between">
            <div class="truncate">
              <p class="text-sm font-semibold text-white truncate">${currentUser.full_name || currentUser.username}</p>
              <p class="text-xs text-gray-400 truncate">${currentUser.employee_number || currentUser.email}</p>
            </div>
            <button onclick="logout()" title="Logout" class="p-2 text-gray-400 hover:text-red-400 hover:bg-navy-800 rounded-lg transition-colors">
              🚪
            </button>
          </div>
        </div>
      </aside>

      <!-- Main Content Stage -->
      <main class="flex-1 flex flex-col overflow-hidden">
        <!-- Top Banner -->
        <header class="h-16 bg-navy-900 border-b border-navy-800 flex items-center justify-between px-8">
          <div class="flex items-center space-x-4">
            <h1 class="text-lg font-bold text-white capitalize">${currentTab.replace('_', ' ')}</h1>
          </div>
          <div class="flex items-center space-x-4">
            <span class="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-green-900/30 text-green-400 border border-green-700/50">
              <span class="w-1.5 h-1.5 mr-1.5 bg-green-400 rounded-full animate-pulse"></span> Backend Connected
            </span>
            <button onclick="refreshCurrentTab()" class="px-3 py-1.5 text-xs bg-navy-800 hover:bg-navy-700 text-gray-300 rounded border border-navy-700 transition-colors">
              ↻ Refresh
            </button>
          </div>
        </header>

        <!-- Stage Body -->
        <div id="tab-content" class="flex-1 overflow-y-auto p-8">
          <div class="flex items-center justify-center h-48 text-gray-400">Loading module data...</div>
        </div>
      </main>
    </div>
  `;

  loadTabContent();
}

function navItem(tabId, label) {
  const active = currentTab === tabId;
  return `
    <button onclick="switchTab('${tabId}')" 
            class="w-full text-left px-3.5 py-2.5 rounded-lg text-sm font-medium transition-all ${
              active 
                ? 'bg-gold-500 text-navy-950 font-bold shadow-md shadow-gold-500/10' 
                : 'text-gray-300 hover:bg-navy-800 hover:text-white'
            }">
      ${label}
    </button>
  `;
}

function switchTab(tabId) {
  currentTab = tabId;
  render();
}

function refreshCurrentTab() {
  loadTabContent();
}

async function loadTabContent() {
  const content = document.getElementById('tab-content');
  if (!content) return;

  try {
    switch (currentTab) {
      case 'dashboard':
        await renderDashboardTab(content);
        break;
      case 'users':
        await renderUsersTab(content);
        break;
      case 'stations':
        await renderStationsTab(content);
        break;
      case 'pairs':
        await renderPairsTab(content);
        break;
      case 'roster':
        await renderRosterTab(content);
        break;
      case 'attendance':
        await renderAttendanceTab(content);
        break;
      case 'handovers':
        await renderHandoversTab(content);
        break;
      case 'incidents':
        await renderIncidentsTab(content);
        break;
      case 'ob':
        await renderOBTab(content);
        break;
      case 'patrols':
        await renderPatrolsTab(content);
        break;
      case 'leave':
        await renderLeaveTab(content);
        break;
      default:
        content.innerHTML = `<p class="text-gray-400">Module view under construction.</p>`;
    }
  } catch (err) {
    content.innerHTML = `
      <div class="p-6 bg-red-950/40 border border-red-800 rounded-xl text-red-300">
        <h3 class="font-bold text-lg mb-2">Error Loading Data</h3>
        <p class="text-sm font-mono">${err.message}</p>
        <button onclick="refreshCurrentTab()" class="mt-4 px-4 py-2 bg-red-800 hover:bg-red-700 text-white rounded text-xs font-semibold">
          Retry Request
        </button>
      </div>
    `;
  }
}

// ---------------------- MODULE RENDERERS ----------------------

async function renderDashboardTab(container) {
  // Fetch real counts from Django backend
  const [usersRes, stationsRes, shiftsRes, incidentsRes, leaveRes] = await Promise.all([
    apiRequest('/accounts/users/'),
    apiRequest('/stations/stations/'),
    apiRequest('/shifts/shifts/'),
    apiRequest('/incidents/reports/'),
    apiRequest('/leave/applications/'),
  ]);

  const users = usersRes.results || usersRes;
  const stations = stationsRes.results || stationsRes;
  const shifts = shiftsRes.results || shiftsRes;
  const incidents = incidentsRes.results || incidentsRes;
  const leaves = leaveRes.results || leaveRes;

  const guardsCount = users.filter(u => u.role === 'GUARD').length;
  const activeIncidents = incidents.filter(i => i.status !== 'RESOLVED').length;
  const pendingLeaves = leaves.filter(l => l.status === 'PENDING').length;

  container.innerHTML = `
    <div class="space-y-8">
      <!-- Stat Cards Grid -->
      <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-6">
        ${statCard('Total Active Stations', stations.length, '🏢', 'bg-blue-900/20 text-blue-400 border-blue-800/40')}
        ${statCard('Deployed Guards', guardsCount, '🛡️', 'bg-gold-900/30 text-gold-500 border-gold-600/40')}
        ${statCard('Active Incidents', activeIncidents, '🚨', activeIncidents > 0 ? 'bg-red-900/30 text-red-400 border-red-800/50' : 'bg-navy-800 text-gray-400 border-navy-700')}
        ${statCard('Pending Leave Requests', pendingLeaves, '📝', pendingLeaves > 0 ? 'bg-amber-900/30 text-amber-400 border-amber-800/50' : 'bg-navy-800 text-gray-400 border-navy-700')}
      </div>

      <!-- Quick Action Controls -->
      <div class="bg-navy-900 border border-navy-800 rounded-xl p-6">
        <h3 class="text-md font-bold text-white mb-4">Command Actions</h3>
        <div class="flex flex-wrap gap-4">
          <button onclick="switchTab('roster')" class="px-4 py-2.5 bg-gold-500 hover:bg-gold-600 text-navy-950 font-bold rounded-lg text-sm shadow">
            + Generate Shift Roster
          </button>
          <button onclick="switchTab('users')" class="px-4 py-2.5 bg-navy-800 hover:bg-navy-700 text-white font-medium rounded-lg text-sm border border-navy-700">
            + Add Security Personnel
          </button>
          <button onclick="switchTab('stations')" class="px-4 py-2.5 bg-navy-800 hover:bg-navy-700 text-white font-medium rounded-lg text-sm border border-navy-700">
            + Create New Station
          </button>
        </div>
      </div>

      <!-- Live Roster Table -->
      <div class="bg-navy-900 border border-navy-800 rounded-xl overflow-hidden shadow-lg">
        <div class="p-6 border-b border-navy-800 flex items-center justify-between">
          <h3 class="font-bold text-white">Recent Duty Assignments</h3>
          <span class="text-xs text-gray-400">Total Shifts: ${shifts.length}</span>
        </div>
        ${shifts.length === 0 
          ? `<p class="p-8 text-center text-gray-400">No shifts generated yet. Visit the Roster engine to generate shifts.</p>`
          : `
            <div class="overflow-x-auto">
              <table class="w-full text-left text-sm text-gray-300">
                <thead class="bg-navy-800 text-xs font-semibold uppercase text-gray-400 border-b border-navy-700">
                  <tr>
                    <th class="p-4">Date</th>
                    <th class="p-4">Station</th>
                    <th class="p-4">Guard</th>
                    <th class="p-4">Type</th>
                    <th class="p-4">Start - End</th>
                    <th class="p-4">Assigned Partner</th>
                  </tr>
                </thead>
                <tbody class="divide-y divide-navy-800 font-mono text-xs">
                  ${shifts.slice(0, 10).map(s => `
                    <tr class="hover:bg-navy-800/50">
                      <td class="p-4 text-white font-bold">${s.date}</td>
                      <td class="p-4 font-sans text-white">${s.station_name}</td>
                      <td class="p-4 font-sans text-gold-500 font-semibold">${s.guard_name} (${s.employee_number || 'N/A'})</td>
                      <td class="p-4"><span class="px-2 py-0.5 rounded text-[10px] font-bold ${s.shift_type === 'DAY' ? 'bg-amber-900/40 text-amber-300' : 'bg-indigo-900/40 text-indigo-300'}">${s.shift_type}</span></td>
                      <td class="p-4">${s.start_time} - ${s.end_time}</td>
                      <td class="p-4 font-sans">${s.partner_name ? `${s.partner_name} (${s.partner_employee_number || ''})` : 'Single'}</td>
                    </tr>
                  `).join('')}
                </tbody>
              </table>
            </div>
          `}
      </div>
    </div>
  `;
}

function statCard(title, count, icon, styleClass) {
  return `
    <div class="p-6 rounded-xl border ${styleClass} bg-navy-900/80 shadow-md flex items-center justify-between">
      <div>
        <p class="text-xs font-semibold uppercase tracking-wider text-gray-400 mb-1">${title}</p>
        <h4 class="text-3xl font-extrabold text-white">${count}</h4>
      </div>
      <div class="text-3xl">${icon}</div>
    </div>
  `;
}

async function renderUsersTab(container) {
  const data = await apiRequest('/accounts/users/');
  const users = data.results || data;

  container.innerHTML = `
    <div class="space-y-6">
      <div class="flex items-center justify-between">
        <div>
          <h2 class="text-xl font-bold text-white">Personnel Management</h2>
          <p class="text-xs text-gray-400">Manage security officers, station supervisors, and administrators.</p>
        </div>
        <button onclick="openCreateUserModal()" class="px-4 py-2 bg-gold-500 hover:bg-gold-600 text-navy-950 font-bold rounded-lg text-sm">
          + Add User Account
        </button>
      </div>

      <div class="bg-navy-900 border border-navy-800 rounded-xl overflow-hidden shadow">
        <table class="w-full text-left text-sm text-gray-300">
          <thead class="bg-navy-800 text-xs font-semibold uppercase text-gray-400 border-b border-navy-700">
            <tr>
              <th class="p-4">Name / ID</th>
              <th class="p-4">Role</th>
              <th class="p-4">Assigned Station</th>
              <th class="p-4">Rank</th>
              <th class="p-4">Status</th>
              <th class="p-4 text-right">Actions</th>
            </tr>
          </thead>
          <tbody class="divide-y divide-navy-800">
            ${users.map(u => `
              <tr class="hover:bg-navy-800/40">
                <td class="p-4">
                  <div class="font-bold text-white">${u.full_name || u.username}</div>
                  <div class="text-xs text-gold-500 font-mono">${u.employee_number || 'No ID'} • ${u.username}</div>
                </td>
                <td class="p-4">
                  <span class="px-2 py-0.5 rounded text-xs font-semibold ${
                    u.role === 'ADMINISTRATOR' ? 'bg-purple-900/40 text-purple-300' :
                    u.role === 'SUPERVISOR' ? 'bg-blue-900/40 text-blue-300' : 'bg-emerald-900/40 text-emerald-300'
                  }">${u.role}</span>
                </td>
                <td class="p-4">${u.station_name || 'Unassigned'}</td>
                <td class="p-4 text-xs text-gray-400">${u.rank || 'Officer'}</td>
                <td class="p-4">
                  <span class="px-2 py-0.5 rounded text-[11px] ${u.is_active ? 'bg-green-900/30 text-green-400' : 'bg-red-900/30 text-red-400'}">
                    ${u.is_active ? 'Active' : 'Inactive'}
                  </span>
                </td>
                <td class="p-4 text-right">
                  <button onclick="toggleUserActive('${u.id}', ${!u.is_active})" class="text-xs px-2.5 py-1 bg-navy-800 hover:bg-navy-700 text-gray-300 rounded border border-navy-700">
                    ${u.is_active ? 'Deactivate' : 'Reactivate'}
                  </button>
                </td>
              </tr>
            `).join('')}
          </tbody>
        </table>
      </div>
    </div>
  `;
}

async function toggleUserActive(userId, makeActive) {
  try {
    await apiRequest(`/accounts/users/${userId}/`, 'PATCH', { is_active: makeActive });
    refreshCurrentTab();
  } catch (err) {
    alert(err.message);
  }
}

async function openCreateUserModal() {
  const stationsRes = await apiRequest('/stations/stations/');
  const stations = stationsRes.results || stationsRes;

  const modalHtml = `
    <div id="modal-overlay" class="fixed inset-0 bg-black/70 flex items-center justify-center p-4 z-50">
      <div class="bg-navy-900 border border-navy-700 rounded-2xl max-w-lg w-full p-6 shadow-2xl space-y-4">
        <div class="flex items-center justify-between pb-3 border-b border-navy-800">
          <h3 class="text-lg font-bold text-white">Create Personnel Account</h3>
          <button onclick="closeModal()" class="text-gray-400 hover:text-white">✕</button>
        </div>
        <form id="create-user-form" class="space-y-4 text-sm">
          <div class="grid grid-cols-2 gap-4">
            <div>
              <label class="block text-xs text-gray-400 mb-1">Username *</label>
              <input type="text" id="new_username" required class="w-full px-3 py-2 bg-navy-800 border border-navy-700 rounded text-white">
            </div>
            <div>
              <label class="block text-xs text-gray-400 mb-1">Employee Number *</label>
              <input type="text" id="new_employee_number" required placeholder="e.g. SEC-1010" class="w-full px-3 py-2 bg-navy-800 border border-navy-700 rounded text-white font-mono">
            </div>
          </div>
          <div class="grid grid-cols-2 gap-4">
            <div>
              <label class="block text-xs text-gray-400 mb-1">First Name</label>
              <input type="text" id="new_first_name" class="w-full px-3 py-2 bg-navy-800 border border-navy-700 rounded text-white">
            </div>
            <div>
              <label class="block text-xs text-gray-400 mb-1">Last Name</label>
              <input type="text" id="new_last_name" class="w-full px-3 py-2 bg-navy-800 border border-navy-700 rounded text-white">
            </div>
          </div>
          <div>
            <label class="block text-xs text-gray-400 mb-1">Email</label>
            <input type="email" id="new_email" class="w-full px-3 py-2 bg-navy-800 border border-navy-700 rounded text-white">
          </div>
          <div class="grid grid-cols-2 gap-4">
            <div>
              <label class="block text-xs text-gray-400 mb-1">Role *</label>
              <select id="new_role" class="w-full px-3 py-2 bg-navy-800 border border-navy-700 rounded text-white">
                <option value="GUARD">Security Guard</option>
                <option value="SUPERVISOR">Supervisor</option>
                <option value="ADMINISTRATOR">Administrator</option>
              </select>
            </div>
            <div>
              <label class="block text-xs text-gray-400 mb-1">Station</label>
              <select id="new_station" class="w-full px-3 py-2 bg-navy-800 border border-navy-700 rounded text-white">
                <option value="">-- None / HQ --</option>
                ${stations.map(s => `<option value="${s.id}">${s.name}</option>`).join('')}
              </select>
            </div>
          </div>
          <div>
            <label class="block text-xs text-gray-400 mb-1">Initial Password *</label>
            <input type="password" id="new_password" required minlength="8" class="w-full px-3 py-2 bg-navy-800 border border-navy-700 rounded text-white">
          </div>
          <div class="pt-4 flex justify-end space-x-3">
            <button type="button" onclick="closeModal()" class="px-4 py-2 bg-navy-800 hover:bg-navy-700 text-gray-300 rounded">Cancel</button>
            <button type="submit" class="px-5 py-2 bg-gold-500 hover:bg-gold-600 text-navy-950 font-bold rounded">Create Account</button>
          </div>
        </form>
      </div>
    </div>
  `;

  document.body.insertAdjacentHTML('beforeend', modalHtml);
  document.getElementById('create-user-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    try {
      await apiRequest('/accounts/users/', 'POST', {
        username: document.getElementById('new_username').value.trim(),
        employee_number: document.getElementById('new_employee_number').value.trim(),
        first_name: document.getElementById('new_first_name').value.trim(),
        last_name: document.getElementById('new_last_name').value.trim(),
        email: document.getElementById('new_email').value.trim(),
        role: document.getElementById('new_role').value,
        station: document.getElementById('new_station').value || null,
        password: document.getElementById('new_password').value,
      });
      closeModal();
      refreshCurrentTab();
    } catch (err) {
      alert(err.message);
    }
  });
}

function closeModal() {
  const modal = document.getElementById('modal-overlay');
  if (modal) modal.remove();
}

async function renderStationsTab(container) {
  const data = await apiRequest('/stations/stations/');
  const stations = data.results || data;

  container.innerHTML = `
    <div class="space-y-6">
      <div class="flex items-center justify-between">
        <div>
          <h2 class="text-xl font-bold text-white">Deployment Stations</h2>
          <p class="text-xs text-gray-400">Security posts and physical deployment locations.</p>
        </div>
        <button onclick="openCreateStationModal()" class="px-4 py-2 bg-gold-500 hover:bg-gold-600 text-navy-950 font-bold rounded-lg text-sm">
          + Add Station
        </button>
      </div>

      <div class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
        ${stations.map(s => `
          <div class="bg-navy-900 border border-navy-800 rounded-xl p-6 shadow-md hover:border-navy-700 transition-colors">
            <div class="flex items-start justify-between">
              <div>
                <h3 class="font-bold text-white text-lg">${s.name}</h3>
                <span class="text-xs font-mono text-gold-500 bg-gold-500/10 px-2 py-0.5 rounded">${s.code}</span>
              </div>
              <span class="w-2.5 h-2.5 rounded-full ${s.is_active ? 'bg-green-500' : 'bg-red-500'}"></span>
            </div>
            <p class="text-xs text-gray-400 mt-3">${s.address || 'No physical address recorded'}</p>
            <div class="mt-4 pt-4 border-t border-navy-800 text-xs text-gray-300 space-y-1 font-mono">
              <div>Coords: ${s.latitude}, ${s.longitude}</div>
              <div>Geofence: ${s.geofence_radius_meters}m radius</div>
              <div>Guards Assigned: ${s.guards_count || 0}</div>
            </div>
          </div>
        `).join('')}
      </div>
    </div>
  `;
}

function openCreateStationModal() {
  const modalHtml = `
    <div id="modal-overlay" class="fixed inset-0 bg-black/70 flex items-center justify-center p-4 z-50">
      <div class="bg-navy-900 border border-navy-700 rounded-2xl max-w-md w-full p-6 shadow-2xl space-y-4">
        <div class="flex items-center justify-between pb-3 border-b border-navy-800">
          <h3 class="text-lg font-bold text-white">Create Security Station</h3>
          <button onclick="closeModal()" class="text-gray-400 hover:text-white">✕</button>
        </div>
        <form id="create-station-form" class="space-y-4 text-sm">
          <div>
            <label class="block text-xs text-gray-400 mb-1">Station Name *</label>
            <input type="text" id="stn_name" required placeholder="e.g. North Gate Command" class="w-full px-3 py-2 bg-navy-800 border border-navy-700 rounded text-white">
          </div>
          <div>
            <label class="block text-xs text-gray-400 mb-1">Station Code *</label>
            <input type="text" id="stn_code" required placeholder="e.g. STN-NG01" class="w-full px-3 py-2 bg-navy-800 border border-navy-700 rounded text-white font-mono">
          </div>
          <div>
            <label class="block text-xs text-gray-400 mb-1">Address</label>
            <textarea id="stn_address" rows="2" class="w-full px-3 py-2 bg-navy-800 border border-navy-700 rounded text-white"></textarea>
          </div>
          <div class="grid grid-cols-2 gap-4">
            <div>
              <label class="block text-xs text-gray-400 mb-1">Latitude</label>
              <input type="number" step="any" id="stn_lat" value="0.0" class="w-full px-3 py-2 bg-navy-800 border border-navy-700 rounded text-white">
            </div>
            <div>
              <label class="block text-xs text-gray-400 mb-1">Longitude</label>
              <input type="number" step="any" id="stn_lon" value="0.0" class="w-full px-3 py-2 bg-navy-800 border border-navy-700 rounded text-white">
            </div>
          </div>
          <div class="pt-4 flex justify-end space-x-3">
            <button type="button" onclick="closeModal()" class="px-4 py-2 bg-navy-800 hover:bg-navy-700 text-gray-300 rounded">Cancel</button>
            <button type="submit" class="px-5 py-2 bg-gold-500 hover:bg-gold-600 text-navy-950 font-bold rounded">Create Station</button>
          </div>
        </form>
      </div>
    </div>
  `;
  document.body.insertAdjacentHTML('beforeend', modalHtml);
  document.getElementById('create-station-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    try {
      await apiRequest('/stations/stations/', 'POST', {
        name: document.getElementById('stn_name').value.trim(),
        code: document.getElementById('stn_code').value.trim(),
        address: document.getElementById('stn_address').value.trim(),
        latitude: parseFloat(document.getElementById('stn_lat').value) || 0.0,
        longitude: parseFloat(document.getElementById('stn_lon').value) || 0.0,
      });
      closeModal();
      refreshCurrentTab();
    } catch (err) {
      alert(err.message);
    }
  });
}

async function renderPairsTab(container) {
  const data = await apiRequest('/stations/pairs/');
  const pairs = data.results || data;

  container.innerHTML = `
    <div class="space-y-6">
      <div class="flex items-center justify-between">
        <div>
          <h2 class="text-xl font-bold text-white">Guard Pairs & Rotations</h2>
          <p class="text-xs text-gray-400">Formal pairing of guards assigned to rotate on shift cycles.</p>
        </div>
        <button onclick="openCreatePairModal()" class="px-4 py-2 bg-gold-500 hover:bg-gold-600 text-navy-950 font-bold rounded-lg text-sm">
          + Create Guard Pair
        </button>
      </div>

      <div class="bg-navy-900 border border-navy-800 rounded-xl overflow-hidden shadow">
        <table class="w-full text-left text-sm text-gray-300">
          <thead class="bg-navy-800 text-xs font-semibold uppercase text-gray-400 border-b border-navy-700">
            <tr>
              <th class="p-4">Station</th>
              <th class="p-4">Rotation Order</th>
              <th class="p-4">Guard A</th>
              <th class="p-4">Guard B</th>
              <th class="p-4">Status</th>
            </tr>
          </thead>
          <tbody class="divide-y divide-navy-800">
            ${pairs.map(p => `
              <tr class="hover:bg-navy-800/40">
                <td class="p-4 font-bold text-white">${p.station_name}</td>
                <td class="p-4 font-mono text-gold-500">Order #${p.rotation_order}</td>
                <td class="p-4 font-semibold text-white">${p.guard_a_name} <span class="text-xs text-gray-400 font-mono">(${p.guard_a_employee_number})</span></td>
                <td class="p-4 font-semibold text-white">${p.guard_b_name} <span class="text-xs text-gray-400 font-mono">(${p.guard_b_employee_number})</span></td>
                <td class="p-4">
                  <span class="px-2 py-0.5 rounded text-[11px] ${p.is_active ? 'bg-green-900/30 text-green-400' : 'bg-gray-800 text-gray-400'}">
                    ${p.is_active ? 'Active Rotation' : 'Inactive'}
                  </span>
                </td>
              </tr>
            `).join('')}
          </tbody>
        </table>
      </div>
    </div>
  `;
}

async function openCreatePairModal() {
  const [stationsRes, guardsRes] = await Promise.all([
    apiRequest('/stations/stations/'),
    apiRequest('/accounts/users/?role=GUARD'),
  ]);
  const stations = stationsRes.results || stationsRes;
  const guards = guardsRes.results || guardsRes;

  const modalHtml = `
    <div id="modal-overlay" class="fixed inset-0 bg-black/70 flex items-center justify-center p-4 z-50">
      <div class="bg-navy-900 border border-navy-700 rounded-2xl max-w-md w-full p-6 shadow-2xl space-y-4">
        <div class="flex items-center justify-between pb-3 border-b border-navy-800">
          <h3 class="text-lg font-bold text-white">Create Guard Pair</h3>
          <button onclick="closeModal()" class="text-gray-400 hover:text-white">✕</button>
        </div>
        <form id="create-pair-form" class="space-y-4 text-sm">
          <div>
            <label class="block text-xs text-gray-400 mb-1">Station *</label>
            <select id="pair_station" required class="w-full px-3 py-2 bg-navy-800 border border-navy-700 rounded text-white">
              ${stations.map(s => `<option value="${s.id}">${s.name}</option>`).join('')}
            </select>
          </div>
          <div>
            <label class="block text-xs text-gray-400 mb-1">Guard A *</label>
            <select id="pair_guard_a" required class="w-full px-3 py-2 bg-navy-800 border border-navy-700 rounded text-white">
              ${guards.map(g => `<option value="${g.id}">${g.full_name || g.username} (${g.employee_number})</option>`).join('')}
            </select>
          </div>
          <div>
            <label class="block text-xs text-gray-400 mb-1">Guard B *</label>
            <select id="pair_guard_b" required class="w-full px-3 py-2 bg-navy-800 border border-navy-700 rounded text-white">
              ${guards.map(g => `<option value="${g.id}">${g.full_name || g.username} (${g.employee_number})</option>`).join('')}
            </select>
          </div>
          <div>
            <label class="block text-xs text-gray-400 mb-1">Rotation Order (Sequence)</label>
            <input type="number" id="pair_rotation" min="1" value="1" class="w-full px-3 py-2 bg-navy-800 border border-navy-700 rounded text-white">
          </div>
          <div class="pt-4 flex justify-end space-x-3">
            <button type="button" onclick="closeModal()" class="px-4 py-2 bg-navy-800 hover:bg-navy-700 text-gray-300 rounded">Cancel</button>
            <button type="submit" class="px-5 py-2 bg-gold-500 hover:bg-gold-600 text-navy-950 font-bold rounded">Create Pair</button>
          </div>
        </form>
      </div>
    </div>
  `;
  document.body.insertAdjacentHTML('beforeend', modalHtml);
  document.getElementById('create-pair-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    try {
      await apiRequest('/stations/pairs/', 'POST', {
        station: document.getElementById('pair_station').value,
        guard_a: document.getElementById('pair_guard_a').value,
        guard_b: document.getElementById('pair_guard_b').value,
        rotation_order: parseInt(document.getElementById('pair_rotation').value) || 1,
      });
      closeModal();
      refreshCurrentTab();
    } catch (err) {
      alert(err.message);
    }
  });
}

async function renderRosterTab(container) {
  const stationsRes = await apiRequest('/stations/stations/');
  const stations = stationsRes.results || stationsRes;

  const shiftsRes = await apiRequest('/shifts/shifts/');
  const shifts = shiftsRes.results || shiftsRes;

  const today = new Date().toISOString().split('T')[0];

  container.innerHTML = `
    <div class="space-y-6">
      <div class="bg-navy-900 border border-navy-800 rounded-xl p-6">
        <h3 class="font-bold text-white text-lg mb-2">Duty Roster Generation Engine</h3>
        <p class="text-xs text-gray-400 mb-6">Generates 4-day rotation blocks for configured guard pairs on a station.</p>
        
        <form id="generate-roster-form" class="grid grid-cols-1 sm:grid-cols-4 gap-4 text-sm">
          <div>
            <label class="block text-xs text-gray-400 mb-1">Target Station</label>
            <select id="roster_station" required class="w-full px-3 py-2 bg-navy-800 border border-navy-700 rounded text-white">
              ${stations.map(s => `<option value="${s.id}">${s.name}</option>`).join('')}
            </select>
          </div>
          <div>
            <label class="block text-xs text-gray-400 mb-1">Start Date</label>
            <input type="date" id="roster_start" value="${today}" required class="w-full px-3 py-2 bg-navy-800 border border-navy-700 rounded text-white">
          </div>
          <div>
            <label class="block text-xs text-gray-400 mb-1">Cycle Duration (Days)</label>
            <input type="number" id="roster_days" value="12" min="1" max="60" class="w-full px-3 py-2 bg-navy-800 border border-navy-700 rounded text-white">
          </div>
          <div class="flex items-end">
            <button type="submit" id="gen-btn" class="w-full py-2 bg-gold-500 hover:bg-gold-600 text-navy-950 font-bold rounded">
              Execute Generation
            </button>
          </div>
        </form>
      </div>

      <div class="bg-navy-900 border border-navy-800 rounded-xl overflow-hidden shadow">
        <div class="p-4 border-b border-navy-800 font-bold text-white">Roster Shifts Schedule (${shifts.length} total)</div>
        <table class="w-full text-left text-sm text-gray-300">
          <thead class="bg-navy-800 text-xs font-semibold uppercase text-gray-400 border-b border-navy-700">
            <tr>
              <th class="p-4">Date</th>
              <th class="p-4">Station</th>
              <th class="p-4">Guard</th>
              <th class="p-4">Type</th>
              <th class="p-4">Time Slot</th>
              <th class="p-4">Assigned Partner</th>
            </tr>
          </thead>
          <tbody class="divide-y divide-navy-800 font-mono text-xs">
            ${shifts.map(s => `
              <tr class="hover:bg-navy-800/40">
                <td class="p-4 font-bold text-white">${s.date}</td>
                <td class="p-4 font-sans text-white">${s.station_name}</td>
                <td class="p-4 font-sans text-gold-500">${s.guard_name} (${s.employee_number})</td>
                <td class="p-4"><span class="px-2 py-0.5 rounded text-[10px] font-bold ${s.shift_type === 'DAY' ? 'bg-amber-900/40 text-amber-300' : 'bg-indigo-900/40 text-indigo-300'}">${s.shift_type}</span></td>
                <td class="p-4">${s.start_time} - ${s.end_time}</td>
                <td class="p-4 font-sans">${s.partner_name || 'Single'}</td>
              </tr>
            `).join('')}
          </tbody>
        </table>
      </div>
    </div>
  `;

  document.getElementById('generate-roster-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const btn = document.getElementById('gen-btn');
    btn.disabled = true;
    btn.innerText = 'Generating...';
    try {
      const res = await apiRequest('/shifts/shifts/generate/', 'POST', {
        station_id: document.getElementById('roster_station').value,
        start_date: document.getElementById('roster_start').value,
        cycle_days: parseInt(document.getElementById('roster_days').value) || 12,
      });
      alert(res.message);
      refreshCurrentTab();
    } catch (err) {
      alert(err.message);
      btn.disabled = false;
      btn.innerText = 'Execute Generation';
    }
  });
}

async function renderAttendanceTab(container) {
  const data = await apiRequest('/shifts/attendance/');
  const records = data.results || data;

  container.innerHTML = `
    <div class="space-y-6">
      <h2 class="text-xl font-bold text-white">Live Attendance & Clock Records</h2>
      <div class="bg-navy-900 border border-navy-800 rounded-xl overflow-hidden shadow">
        <table class="w-full text-left text-sm text-gray-300">
          <thead class="bg-navy-800 text-xs font-semibold uppercase text-gray-400 border-b border-navy-700">
            <tr>
              <th class="p-4">Shift Date</th>
              <th class="p-4">Guard</th>
              <th class="p-4">Station</th>
              <th class="p-4">Clock In Time / GPS</th>
              <th class="p-4">Clock Out Time / GPS</th>
              <th class="p-4">Late Status</th>
            </tr>
          </thead>
          <tbody class="divide-y divide-navy-800 text-xs font-mono">
            ${records.length === 0 ? `<tr><td colspan="6" class="p-8 text-center text-gray-400">No attendance clock-in records logged yet.</td></tr>` : ''}
            ${records.map(r => `
              <tr class="hover:bg-navy-800/40">
                <td class="p-4 text-white font-bold">${r.shift_date} (${r.shift_type})</td>
                <td class="p-4 font-sans text-white">${r.guard_name} (${r.guard_employee_number})</td>
                <td class="p-4 font-sans">${r.station_name}</td>
                <td class="p-4">
                  ${r.clock_in ? `<span class="text-emerald-400 font-bold">${new Date(r.clock_in).toLocaleTimeString()}</span> <div class="text-[10px] text-gray-400">${r.clock_in_gps || 'No GPS'}</div>` : '<span class="text-gray-500">Pending</span>'}
                </td>
                <td class="p-4">
                  ${r.clock_out ? `<span class="text-blue-400 font-bold">${new Date(r.clock_out).toLocaleTimeString()}</span> <div class="text-[10px] text-gray-400">${r.clock_out_gps || 'No GPS'}</div>` : '<span class="text-gray-500">On Duty</span>'}
                </td>
                <td class="p-4">
                  ${r.is_late 
                    ? `<span class="px-2 py-0.5 rounded text-[10px] bg-red-900/40 text-red-400 border border-red-800">LATE: ${r.late_reason || 'Unscheduled delay'}</span>`
                    : `<span class="px-2 py-0.5 rounded text-[10px] bg-green-900/30 text-green-400">ON TIME</span>`}
                </td>
              </tr>
            `).join('')}
          </tbody>
        </table>
      </div>
    </div>
  `;
}

async function renderHandoversTab(container) {
  const data = await apiRequest('/shifts/handovers/');
  const handovers = data.results || data;

  container.innerHTML = `
    <div class="space-y-6">
      <h2 class="text-xl font-bold text-white">Shift Handovers Monitor</h2>
      <div class="space-y-4">
        ${handovers.length === 0 ? `<p class="p-8 text-center text-gray-400 bg-navy-900 rounded-xl border border-navy-800">No shift handovers submitted yet.</p>` : ''}
        ${handovers.map(h => `
          <div class="bg-navy-900 border border-navy-800 rounded-xl p-6 space-y-3">
            <div class="flex items-center justify-between border-b border-navy-800 pb-3">
              <div>
                <span class="font-bold text-white text-base">${h.station_name}</span>
                <span class="text-xs text-gray-400 ml-2">(${h.outgoing_shift_details})</span>
              </div>
              <span class="px-2.5 py-1 rounded text-xs font-semibold ${h.incoming_accepted ? 'bg-green-900/40 text-green-400' : 'bg-amber-900/40 text-amber-400'}">
                ${h.incoming_accepted ? 'Accepted by Incoming Guard' : 'Pending Incoming Acceptance'}
              </span>
            </div>
            <div class="grid grid-cols-2 gap-4 text-xs font-mono">
              <div>Outgoing Guard: <span class="text-white font-bold">${h.outgoing_guard_name}</span></div>
              <div>Incoming Guard: <span class="text-gold-500 font-bold">${h.incoming_guard_name}</span></div>
            </div>
            <div class="text-xs text-gray-300 space-y-1 bg-navy-800/40 p-3 rounded-lg border border-navy-800">
              <div><strong class="text-gray-400">Occurrence:</strong> ${h.occurrence_summary}</div>
              <div><strong class="text-gray-400">Equipment:</strong> ${h.equipment_issued}</div>
              <div><strong class="text-gray-400">Keys:</strong> ${h.keys_handed_over}</div>
              <div><strong class="text-gray-400">Issues:</strong> ${h.pending_issues}</div>
            </div>
          </div>
        `).join('')}
      </div>
    </div>
  `;
}

async function renderIncidentsTab(container) {
  const data = await apiRequest('/incidents/reports/');
  const incidents = data.results || data;

  container.innerHTML = `
    <div class="space-y-6">
      <h2 class="text-xl font-bold text-white">Incident Command & Dispatch</h2>
      <div class="space-y-4">
        ${incidents.length === 0 ? `<p class="p-8 text-center text-gray-400 bg-navy-900 rounded-xl border border-navy-800">No incident reports logged.</p>` : ''}
        ${incidents.map(inc => `
          <div class="bg-navy-900 border ${inc.priority === 'CRITICAL' ? 'border-red-600/80 bg-red-950/20' : 'border-navy-800'} rounded-xl p-6 space-y-3">
            <div class="flex items-center justify-between">
              <div class="flex items-center space-x-3">
                <span class="px-2.5 py-1 rounded text-xs font-bold ${
                  inc.priority === 'CRITICAL' ? 'bg-red-900 text-red-200 animate-pulse' :
                  inc.priority === 'HIGH' ? 'bg-orange-900/50 text-orange-300' : 'bg-navy-800 text-gray-300'
                }">${inc.priority}</span>
                <h3 class="font-bold text-white text-lg">${inc.title}</h3>
              </div>
              <span class="text-xs text-gray-400">${new Date(inc.created_at).toLocaleString()}</span>
            </div>
            <p class="text-sm text-gray-300">${inc.description}</p>
            <div class="flex flex-wrap items-center justify-between pt-3 border-t border-navy-800 text-xs text-gray-400">
              <div>Station: <span class="text-white">${inc.station_name}</span> (${inc.location}) • Reporter: <span class="text-gold-500">${inc.reporting_guard_name}</span></div>
              <div class="flex items-center space-x-2 mt-2 sm:mt-0">
                <span class="font-semibold text-gray-300">Status: ${inc.status}</span>
                ${inc.status === 'REPORTED' ? `
                  <button onclick="acknowledgeIncident('${inc.id}')" class="px-3 py-1 bg-navy-800 hover:bg-navy-700 text-blue-300 rounded border border-navy-700 text-xs">
                    Acknowledge
                  </button>
                ` : ''}
                ${inc.status !== 'RESOLVED' ? `
                  <button onclick="resolveIncident('${inc.id}')" class="px-3 py-1 bg-green-900/40 hover:bg-green-800/60 text-green-300 rounded border border-green-700 text-xs font-bold">
                    Resolve
                  </button>
                ` : ''}
              </div>
            </div>
          </div>
        `).join('')}
      </div>
    </div>
  `;
}

async function acknowledgeIncident(id) {
  try {
    await apiRequest(`/incidents/reports/${id}/acknowledge/`, 'POST');
    refreshCurrentTab();
  } catch (err) {
    alert(err.message);
  }
}

async function resolveIncident(id) {
  const notes = prompt('Enter resolution summary / debrief notes:');
  if (notes !== null) {
    try {
      await apiRequest(`/incidents/reports/${id}/resolve/`, 'POST', { resolution_notes: notes });
      refreshCurrentTab();
    } catch (err) {
      alert(err.message);
    }
  }
}

async function renderOBTab(container) {
  const data = await apiRequest('/occurrence_book/entries/');
  const entries = data.results || data;

  container.innerHTML = `
    <div class="space-y-6">
      <h2 class="text-xl font-bold text-white">Official Occurrence Book (OB)</h2>
      <div class="bg-navy-900 border border-navy-800 rounded-xl overflow-hidden shadow">
        <table class="w-full text-left text-sm text-gray-300">
          <thead class="bg-navy-800 text-xs font-semibold uppercase text-gray-400 border-b border-navy-700">
            <tr>
              <th class="p-4">Entry #</th>
              <th class="p-4">Timestamp</th>
              <th class="p-4">Station</th>
              <th class="p-4">Category</th>
              <th class="p-4">Occurrence Text</th>
              <th class="p-4">Logged By</th>
            </tr>
          </thead>
          <tbody class="divide-y divide-navy-800 font-mono text-xs">
            ${entries.length === 0 ? `<tr><td colspan="6" class="p-8 text-center text-gray-400">No OB records logged yet.</td></tr>` : ''}
            ${entries.map(e => `
              <tr class="hover:bg-navy-800/40">
                <td class="p-4 font-bold text-gold-500">${e.entry_number}</td>
                <td class="p-4 text-gray-400">${new Date(e.created_at).toLocaleString()}</td>
                <td class="p-4 font-sans text-white">${e.station_name}</td>
                <td class="p-4"><span class="px-2 py-0.5 rounded text-[10px] bg-navy-800 text-gray-300">${e.category_display || e.category}</span></td>
                <td class="p-4 font-sans text-gray-200 max-w-md">${e.occurrence_text}</td>
                <td class="p-4 font-sans text-gray-400">${e.guard_name}</td>
              </tr>
            `).join('')}
          </tbody>
        </table>
      </div>
    </div>
  `;
}

async function renderPatrolsTab(container) {
  const [logsRes, checkRes] = await Promise.all([
    apiRequest('/patrols/logs/'),
    apiRequest('/patrols/checkpoints/'),
  ]);
  const logs = logsRes.results || logsRes;
  const checkpoints = checkRes.results || checkRes;

  container.innerHTML = `
    <div class="space-y-6">
      <h2 class="text-xl font-bold text-white">Patrol Logs & Station Checkpoints</h2>
      <div class="grid grid-cols-1 md:grid-cols-2 gap-6">
        <div class="bg-navy-900 border border-navy-800 rounded-xl p-6">
          <h3 class="font-bold text-white mb-4">Active Station Checkpoints</h3>
          <div class="space-y-2">
            ${checkpoints.map(cp => `
              <div class="flex items-center justify-between p-3 bg-navy-800/50 rounded-lg border border-navy-800 text-xs">
                <div>
                  <span class="font-bold text-white">${cp.name}</span>
                  <div class="font-mono text-gold-500">${cp.code} • ${cp.station_name}</div>
                </div>
                <span class="text-gray-400 font-mono">Order #${cp.order}</span>
              </div>
            `).join('')}
          </div>
        </div>

        <div class="bg-navy-900 border border-navy-800 rounded-xl p-6">
          <h3 class="font-bold text-white mb-4">Completed & Active Patrols</h3>
          <div class="space-y-3">
            ${logs.length === 0 ? `<p class="text-xs text-gray-400">No patrol logs found.</p>` : ''}
            ${logs.map(l => `
              <div class="p-3 bg-navy-800/40 rounded-lg border border-navy-800 text-xs">
                <div class="flex items-center justify-between">
                  <span class="font-bold text-white">${l.guard_name} @ ${l.station_name}</span>
                  <span class="px-2 py-0.5 rounded text-[10px] font-bold ${l.status === 'COMPLETED' ? 'bg-green-900/30 text-green-400' : 'bg-blue-900/30 text-blue-400'}">${l.status}</span>
                </div>
                <div class="text-gray-400 mt-1">Scans: ${l.scans_count || 0} checkpoints verified</div>
              </div>
            `).join('')}
          </div>
        </div>
      </div>
    </div>
  `;
}

async function renderLeaveTab(container) {
  const data = await apiRequest('/leave/applications/');
  const leaves = data.results || data;

  container.innerHTML = `
    <div class="space-y-6">
      <h2 class="text-xl font-bold text-white">Leave Applications & Approvals</h2>
      <div class="bg-navy-900 border border-navy-800 rounded-xl overflow-hidden shadow">
        <table class="w-full text-left text-sm text-gray-300">
          <thead class="bg-navy-800 text-xs font-semibold uppercase text-gray-400 border-b border-navy-700">
            <tr>
              <th class="p-4">Guard</th>
              <th class="p-4">Type</th>
              <th class="p-4">Dates</th>
              <th class="p-4">Reason</th>
              <th class="p-4">Status</th>
              <th class="p-4 text-right">Review</th>
            </tr>
          </thead>
          <tbody class="divide-y divide-navy-800 text-xs">
            ${leaves.length === 0 ? `<tr><td colspan="6" class="p-8 text-center text-gray-400">No leave applications on record.</td></tr>` : ''}
            ${leaves.map(l => `
              <tr class="hover:bg-navy-800/40">
                <td class="p-4 font-bold text-white">${l.guard_name} (${l.guard_employee_number})</td>
                <td class="p-4 font-semibold text-gold-500">${l.leave_type_display || l.leave_type}</td>
                <td class="p-4 font-mono">${l.start_date} to ${l.end_date}</td>
                <td class="p-4 max-w-xs text-gray-300">${l.reason}</td>
                <td class="p-4">
                  <span class="px-2 py-0.5 rounded text-[11px] font-bold ${
                    l.status === 'APPROVED' ? 'bg-green-900/40 text-green-400' :
                    l.status === 'REJECTED' ? 'bg-red-900/40 text-red-400' : 'bg-amber-900/40 text-amber-400'
                  }">${l.status}</span>
                </td>
                <td class="p-4 text-right space-x-2">
                  ${l.status === 'PENDING' ? `
                    <button onclick="reviewLeave('${l.id}', 'APPROVED')" class="px-2.5 py-1 bg-green-900/40 hover:bg-green-800/60 text-green-300 rounded border border-green-700">
                      Approve
                    </button>
                    <button onclick="reviewLeave('${l.id}', 'REJECTED')" class="px-2.5 py-1 bg-red-900/40 hover:bg-red-800/60 text-red-300 rounded border border-red-700">
                      Reject
                    </button>
                  ` : `<span class="text-gray-500 font-mono text-[10px]">Reviewed by ${l.reviewer_name || 'Supervisor'}</span>`}
                </td>
              </tr>
            `).join('')}
          </tbody>
        </table>
      </div>
    </div>
  `;
}

async function reviewLeave(id, newStatus) {
  const notes = prompt(`Enter review notes for ${newStatus}:`, '');
  try {
    await apiRequest(`/leave/applications/${id}/review/`, 'POST', {
      status: newStatus,
      reviewer_notes: notes || '',
    });
    refreshCurrentTab();
  } catch (err) {
    alert(err.message);
  }
}

// Initial Boot
document.addEventListener('DOMContentLoaded', render);
