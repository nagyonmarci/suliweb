document.addEventListener('DOMContentLoaded', () => {

    // --- State Elements ---
    const systemStatusText = document.getElementById('system-status-text');
    const systemStatusDot = document.getElementById('system-status-dot');
    const logContainer = document.getElementById('log-container');
    const toastContainer = document.getElementById('toast-container');

    // --- Buttons & Inputs ---
    const btnSearchDb = document.getElementById('btn-search-db');
    const btnUpdateDb = document.getElementById('btn-update-db');
    const searchDirsInput = document.getElementById('search-dirs');
    const excludeDirsInput = document.getElementById('exclude-dirs');

    // Processing buttons
    const uploadZone = document.getElementById('upload-zone');
    const fileUpload = document.getElementById('pst-file-upload');
    const btnProcessDb = document.getElementById('btn-process-db');
    const saveAttachmentsToggle = document.getElementById('save-attachments');

    // Queue control
    const btnPause = document.getElementById('btn-pause');
    const btnResume = document.getElementById('btn-resume');

    // DB Viewer
    const btnLoadEmails = document.getElementById('btn-load-emails');
    const emailsTableBody = document.getElementById('emails-table-body');
    const btnLoadFiles = document.getElementById('btn-load-files');
    const filesTableBody = document.getElementById('files-table-body');

    // Progress Tracking
    const progressContainer = document.getElementById('progress-container');
    const progressOperationName = document.getElementById('progress-operation-name');
    const progressText = document.getElementById('progress-text');
    const progressBarFill = document.getElementById('progress-bar-fill');
    let progressPollInterval = null;

    // --- Progress Bar Logic ---

    function startProgressPolling() {
        if (progressPollInterval) clearInterval(progressPollInterval);
        progressContainer.style.display = 'block';

        progressPollInterval = setInterval(async () => {
            try {
                const res = await fetch('/api/progress');
                if (!res.ok) return;
                const state = await res.json();

                if (state.active) {
                    progressOperationName.textContent = state.currentOperation || 'Feldolgozás folyamatban...';
                    progressText.textContent = `${state.percentage}% (${state.processedItems} / ${state.totalItems})`;
                    progressBarFill.style.width = `${state.percentage}%`;
                } else if (state.percentage === 100) {
                    progressOperationName.textContent = 'Feldolgozás befejezve!';
                    progressText.textContent = `100% (${state.totalItems} / ${state.totalItems})`;
                    progressBarFill.style.width = `100%`;
                    progressBarFill.style.backgroundColor = 'var(--success)';
                    clearInterval(progressPollInterval);
                    setTimeout(() => {
                        progressContainer.style.display = 'none';
                        progressBarFill.style.backgroundColor = 'var(--accent)';
                    }, 5000);
                } else {
                    progressContainer.style.display = 'none';
                    clearInterval(progressPollInterval);
                }
            } catch (err) {
                console.error("Hiba a progress lekérdezésekor", err);
            }
        }, 1000); // 1 sec poll
    }

    // --- Utility: Logging & Notifications ---

    function logMessage(message, type = 'info') {
        const entry = document.createElement('div');
        entry.className = `log-entry ${type}`;

        const timestamp = new Date().toLocaleTimeString('hu-HU', { hour12: false });
        entry.textContent = `[${timestamp}] ${message}`;

        logContainer.appendChild(entry);
        logContainer.scrollTop = logContainer.scrollHeight;
    }

    function showToast(message, type = 'info') {
        const toast = document.createElement('div');
        toast.className = `toast ${type}`;
        toast.innerHTML = `<span>${message}</span>`;
        toastContainer.appendChild(toast);

        // Remove after animation (approx 4.3s)
        setTimeout(() => {
            if (toastContainer.contains(toast)) {
                toastContainer.removeChild(toast);
            }
        }, 4500);
    }

    function setSystemStatus(status, text) {
        systemStatusDot.className = `status-dot ${status}`;
        systemStatusText.textContent = text;
    }

    // --- API Wrapper ---
    async function apiCall(url, method = 'GET', body = null, isFormData = false) {
        setSystemStatus('busy', 'Feldolgozás alatt...');

        const options = {
            method: method,
        };

        if (body) {
            options.body = body; // Ha FormData, a böngésző automatikusan beállítja a multipart headert
        }

        try {
            const response = await fetch(url, options);
            const textResponse = await response.text();

            if (!response.ok) {
                throw new Error(textResponse || `HTTP ${response.status}`);
            }

            setSystemStatus('online', 'Rendszer Aktív');
            return textResponse;
        } catch (error) {
            setSystemStatus('online', 'Rendszer Aktív');
            logMessage(`Hiba történt: ${error.message}`, 'error');
            showToast('Hiba a kérés során', 'error');
            throw error;
        }
    }

    // --- Event Listeners: Search & DB ---

    btnSearchDb.addEventListener('click', async () => {
        const dirs = searchDirsInput.value.trim();
        const excludes = excludeDirsInput.value.trim();

        if (!dirs) {
            showToast("Kérlek add meg a keresési könyvtárat!", "warning");
            return;
        }

        logMessage(`Keresés indítása: ${dirs}`, 'info');
        btnSearchDb.disabled = true;

        try {
            let url = `/find/pst?directories=${encodeURIComponent(dirs)}`;
            if (excludes) {
                url += `&excludedDirectories=${encodeURIComponent(excludes)}`;
            }

            const result = await apiCall(url, 'GET');
            logMessage(result, 'success');
            showToast("Keresés sikeresen befejeződött", "success");
        } catch (e) {
            // Error handled in apiCall
        } finally {
            btnSearchDb.disabled = false;
        }
    });

    btnUpdateDb.addEventListener('click', async () => {
        logMessage("Adatbázis szinkronizáció indítása...", 'info');
        btnUpdateDb.disabled = true;

        try {
            const result = await apiCall('/find/updateDb', 'GET');
            logMessage(result, 'success');
            showToast("DB sikeresen frissítve", "success");
        } catch (e) { } finally {
            btnUpdateDb.disabled = false;
        }
    });

    // --- Drag & Drop Flow ---

    uploadZone.addEventListener('click', () => fileUpload.click());

    uploadZone.addEventListener('dragover', (e) => {
        e.preventDefault();
        uploadZone.classList.add('dragover');
    });

    uploadZone.addEventListener('dragleave', () => {
        uploadZone.classList.remove('dragover');
    });

    uploadZone.addEventListener('drop', (e) => {
        e.preventDefault();
        uploadZone.classList.remove('dragover');
        if (e.dataTransfer.files.length) {
            fileUpload.files = e.dataTransfer.files;
            handleFileUpload(fileUpload.files[0]);
        }
    });

    fileUpload.addEventListener('change', (e) => {
        if (e.target.files.length) {
            handleFileUpload(e.target.files[0]);
        }
    });

    async function handleFileUpload(file) {
        if (!file.name.toLowerCase().endsWith('.pst')) {
            showToast("Csak .pst kiterjesztésű fájlok tölthetők fel!", "error");
            fileUpload.value = '';
            return;
        }

        const saveAttachments = saveAttachmentsToggle.checked;
        logMessage(`Ideiglenes feltöltés indítása: ${file.name}...`, 'info');
        uploadZone.style.pointerEvents = 'none';
        uploadZone.style.opacity = '0.5';

        startProgressPolling();

        const formData = new FormData();
        formData.append('file', file);
        formData.append('saveAttachments', saveAttachments);

        try {
            const result = await apiCall(`/pst/processFromFile`, 'POST', formData, true);
            logMessage(result, 'success');
            showToast("Fájl sikeresen feldolgozva!", "success");
        } catch (e) { } finally {
            uploadZone.style.pointerEvents = 'auto';
            uploadZone.style.opacity = '1';
            fileUpload.value = ''; // Reset
        }
    }

    // --- DB Processing ---

    btnProcessDb.addEventListener('click', async () => {
        const saveAttachments = saveAttachmentsToggle.checked;
        logMessage("Tömeges feldolgozás indítása a DB-ből beolvasott PST-kre...", 'info');
        btnProcessDb.disabled = true;

        startProgressPolling();

        const formData = new FormData();
        formData.append('saveAttachments', saveAttachments);

        try {
            const result = await apiCall('/pst/processFromDb', 'POST', formData, true);
            logMessage(result, 'success');
            showToast("DB feldolgozás folyamatban...", "success");
        } catch (e) { } finally {
            btnProcessDb.disabled = false;
        }
    });

    // --- Pause / Resume ---

    btnPause.addEventListener('click', async () => {
        logMessage("Szüneteltetés kérése...", 'warning');
        try {
            const result = await apiCall('/pst/pause', 'POST');
            logMessage(result, 'warning');
            showToast("Feldolgozás szüneteltetve", "warning");
        } catch (e) { }
    });

    btnResume.addEventListener('click', async () => {
        logMessage("Folytatás kérése...", 'info');
        try {
            const result = await apiCall('/pst/resume', 'POST');
            logMessage(result, 'success');
            showToast("Feldolgozás folytatódik", "success");
        } catch (e) { }
    });

    // --- DB Viewer Data Loading ---

    // --- Advanced Table State (Sorting & Filtering) ---

    let searchState = {
        subject: '',
        sender: '',
        pstFile: '',
        importance: '',
        sortBy: 'receivedTime',
        direction: 'desc'
    };

    // --- Search & Sort Interaction ---

    const filterSubject = document.getElementById('filter-subject');
    const filterSender = document.getElementById('filter-sender');
    const filterPst = document.getElementById('filter-pst');
    const filterImportance = document.getElementById('filter-importance');
    const sortableHeaders = document.querySelectorAll('.sortable');

    function debounce(func, timeout = 500) {
        let timer;
        return (...args) => {
            clearTimeout(timer);
            timer = setTimeout(() => { func.apply(this, args); }, timeout);
        };
    }

    const triggerSearch = debounce(() => {
        searchState.subject = filterSubject.value;
        searchState.sender = filterSender.value;
        searchState.pstFile = filterPst.value;
        searchState.importance = filterImportance.value;
        loadEmails();
    });

    filterSubject.addEventListener('input', triggerSearch);
    filterSender.addEventListener('input', triggerSearch);
    filterPst.addEventListener('input', triggerSearch);
    filterImportance.addEventListener('change', triggerSearch);

    sortableHeaders.forEach(header => {
        header.addEventListener('click', () => {
            const field = header.dataset.sort;
            if (searchState.sortBy === field) {
                searchState.direction = searchState.direction === 'asc' ? 'desc' : 'asc';
            } else {
                searchState.sortBy = field;
                searchState.direction = 'asc';
            }

            // Update UI Icons
            sortableHeaders.forEach(h => {
                h.classList.remove('active');
                h.querySelector('.sort-icon').textContent = '⇅';
            });
            header.classList.add('active');
            header.querySelector('.sort-icon').textContent = searchState.direction === 'asc' ? '↑' : '↓';

            loadEmails();
        });
    });

    // --- DB Viewer Data Loading ---

    const loadEmails = async () => {
        logMessage("Emailek keresése...", 'info');
        btnLoadEmails.disabled = true;
        btnLoadEmails.innerHTML = '<span class="status-dot busy"></span> Keresés...';

        try {
            setSystemStatus('busy', 'Keresés az adatbázisban...');

            // Build URL with search state
            const params = new URLSearchParams();
            if (searchState.subject) params.append('subject', searchState.subject);
            if (searchState.sender) params.append('sender', searchState.sender);
            if (searchState.pstFile) params.append('pstFile', searchState.pstFile);
            if (searchState.importance) params.append('importance', searchState.importance);
            params.append('sortBy', searchState.sortBy);
            params.append('direction', searchState.direction);
            params.append('limit', 100);

            const response = await fetch(`/api/emails/search?${params.toString()}`);

            if (!response.ok) throw new Error(`HTTP ${response.status}`);
            const data = await response.json();

            setSystemStatus('online', 'Rendszer Aktív');

            emailsTableBody.innerHTML = '';

            if (!data || data.length === 0) {
                emailsTableBody.innerHTML = '<tr><td colspan="7" class="empty-state">Nincs a feltételeknek megfelelő email.</td></tr>';
                return;
            }

            data.forEach(email => {
                const tr = document.createElement('tr');
                tr.dataset.emailId = email.id;

                let dateStr = email.receivedTime || '-';
                if (dateStr !== '-') {
                    const d = new Date(dateStr);
                    dateStr = d.toLocaleString('hu-HU');
                }

                // Importance Badge
                let impBadge = '';
                if (email.importance === 2) impBadge = '<span class="badge badge-importance-high">Magas</span>';
                else if (email.importance === 0) impBadge = '<span class="badge badge-low">Alacsony</span>';
                else impBadge = '<span class="badge badge-importance-normal">Normál</span>';

                // Status Badge
                const statusBadge = email.isRead
                    ? '<span class="badge badge-read">Olvasott</span>'
                    : '<span class="badge badge-unread">Új</span>';

                tr.innerHTML = `
                    <td class="sticky-col" style="white-space: nowrap;">${dateStr}</td>
                    <td class="sticky-col" style="white-space: nowrap; left: 140px;"><div style="max-width: 150px; overflow: hidden; text-overflow: ellipsis;" title="${email.senderName || ''}">${email.senderName || email.senderEmailAddress || '-'}</div></td>
                    <td><div style="max-width: 250px; overflow: hidden; text-overflow: ellipsis;" title="${email.subject || ''}"><strong>${email.subject || '(Nincs Tárgy)'}</strong></div></td>
                    <td><div style="max-width: 200px; overflow: hidden; text-overflow: ellipsis;" title="${(email.recipients || []).join(', ')}">${(email.recipients || []).join(', ') || '-'}</div></td>
                    <td><div style="max-width: 120px; overflow: hidden; text-overflow: ellipsis;">${email.pstFileName || '-'}</div></td>
                    <td><div style="max-width: 150px; overflow: hidden; text-overflow: ellipsis;" title="${email.folderPath || ''}">${email.folderPath || '-'}</div></td>
                    <td>${impBadge}</td>
                    <td>${statusBadge}</td>
                    <td><div style="max-width: 150px; overflow: hidden; text-overflow: ellipsis;" title="${email.internetMessageId || ''}">${email.internetMessageId || '-'}</div></td>
                    <td><div style="max-width: 200px; overflow: hidden; text-overflow: ellipsis;" title="${email.conversationTopic || ''}">${email.conversationTopic || '-'}</div></td>
                    <td><div style="max-width: 120px; overflow: hidden; text-overflow: ellipsis;" title="${email.conversationId || ''}">${email.conversationId || '-'}</div></td>
                    <td>${email.messageClass || '-'}</td>
                    <td>${formatDate(email.creationTime) || '-'}</td>
                    <td>${formatDate(email.lastModificationTime) || '-'}</td>
                    <td>${formatDate(email.clientSubmitTime) || '-'}</td>
                    <td><button class="btn btn-secondary btn-sm" style="padding: 0.25rem 0.5rem; font-size: 0.75rem;">Megnyitás</button></td>
                `;

                tr.addEventListener('click', () => openEmailModal(email));
                emailsTableBody.appendChild(tr);
            });

            logMessage(`${data.length} találat megjelenítve.`, 'success');

        } catch (e) {
            setSystemStatus('online', 'Rendszer Aktív');
            logMessage(`Hiba a kereséskor: ${e.message}`, 'error');
            emailsTableBody.innerHTML = '<tr><td colspan="7" class="empty-state" style="color: var(--danger);">Hiba történt a keresés közben.</td></tr>';
        } finally {
            btnLoadEmails.disabled = false;
            btnLoadEmails.innerHTML = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="18" height="18"><path d="M21 2v6h-6"></path><path d="M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"></path><path d="M3 3v6h6"></path></svg> Adatok Frissítése`;
        }
    };

    btnLoadEmails.addEventListener('click', loadEmails);

    // --- Modal Logic ---

    const emailModal = document.getElementById('email-modal');
    const btnCloseModal = document.getElementById('btn-close-modal');
    const modalTabs = document.querySelectorAll('.tab-btn');
    const tabContents = document.querySelectorAll('.tab-content');

    function openEmailModal(email) {
        document.getElementById('modal-subject').textContent = email.subject || '(Nincs Tárgy)';
        document.getElementById('modal-sender').textContent = `${email.senderName || ''} <${email.senderEmailAddress || ''}>`;

        let dateStr = email.receivedTime || '-';
        if (dateStr !== '-') {
            const d = new Date(dateStr);
            dateStr = d.toLocaleString('hu-HU');
        }
        document.getElementById('modal-date').textContent = dateStr;
        document.getElementById('modal-recipients').textContent = (email.recipients || []).join(', ') || '-';

        // HTML Content
        const iframe = document.getElementById('modal-html-frame');
        const doc = iframe.contentDocument || iframe.contentWindow.document;
        doc.open();
        doc.write(email.htmlContent || email.body || '<i>Nincs megjeleníthető tartalom.</i>');
        doc.close();

        // Plain Text
        document.getElementById('modal-body-text').textContent = email.body || '';

        // Headers
        document.getElementById('modal-headers-text').textContent = email.transportMessageHeaders || 'Nincsenek elérhető fejlécek.';

        // Metadata
        const metaList = document.getElementById('modal-meta-list');
        metaList.innerHTML = '';

        const metaData = [
            { key: 'Message ID', val: email.internetMessageId },
            { key: 'Üzenet Osztály', val: email.messageClass },
            { key: 'Fontosság', val: getImportanceLabel(email.importance) },
            { key: 'Kategóriák', val: (email.categories || []).join(', ') },
            { key: 'Beszélgetés Tárgya', val: email.conversationTopic },
            { key: 'Beszélgetés ID', val: email.conversationId },
            { key: 'Olvasott', val: email.isRead ? 'Igen' : 'Nem' },
            { key: 'Létrehozva', val: formatDate(email.creationTime) },
            { key: 'Utoljára Módosítva', val: formatDate(email.lastModificationTime) },
            { key: 'Beküldve', val: formatDate(email.clientSubmitTime) },
            { key: 'PST Fájl', val: email.pstFileName },
            { key: 'Adatbázis ID', val: email.id }
        ];

        metaData.forEach(item => {
            if (item.val) {
                const entry = document.createElement('div');
                entry.className = 'meta-entry';
                entry.innerHTML = `<span class="key">${item.key}:</span> <span class="val">${item.val}</span>`;
                metaList.appendChild(entry);
            }
        });

        // Reset tabs
        switchTab('tab-html');
        emailModal.style.display = 'flex';
    }

    function getImportanceLabel(imp) {
        if (imp === 2) return 'Magas';
        if (imp === 0) return 'Alacsony';
        if (imp === 1) return 'Normál';
        return imp;
    }

    function formatDate(dateStr) {
        if (!dateStr) return null;
        const d = new Date(dateStr);
        return d.toLocaleString('hu-HU');
    }

    function switchTab(tabId) {
        tabContents.forEach(content => {
            content.style.display = content.id === tabId ? 'block' : 'none';
        });
        modalTabs.forEach(btn => {
            btn.classList.toggle('active', btn.dataset.tab === tabId);
        });
    }

    modalTabs.forEach(btn => {
        btn.addEventListener('click', () => switchTab(btn.dataset.tab));
    });

    btnCloseModal.addEventListener('click', () => {
        emailModal.style.display = 'none';
    });

    window.addEventListener('click', (e) => {
        if (e.target === emailModal) emailModal.style.display = 'none';
    });

    btnLoadFiles.addEventListener('click', async () => {
        logMessage("PST fájlok betöltése az adatbázisból...", 'info');
        btnLoadFiles.disabled = true;
        btnLoadFiles.innerHTML = '<span class="status-dot busy"></span> Töltés...';

        try {
            setSystemStatus('busy', 'Fájlok lekérdezése...');
            const response = await fetch('/api/file-infos');

            if (!response.ok) throw new Error(`HTTP ${response.status}`);
            const data = await response.json();

            setSystemStatus('online', 'Rendszer Aktív');

            // Táblázat feltöltése
            filesTableBody.innerHTML = '';

            if (!data || data.length === 0) {
                filesTableBody.innerHTML = '<tr><td colspan="5" class="empty-state">Nincs megjeleníthető PST fájl az adatbázisban.</td></tr>';
                showToast("Nincs fájl az adatbázisban", "info");
                return;
            }

            const displayData = data.slice(-100).reverse(); // Mutassuk az utolsó 100-at

            displayData.forEach(file => {
                const tr = document.createElement('tr');

                // Formázott dátum
                let dateStr = file.lastModified || '-';
                if (dateStr !== '-') {
                    const d = new Date(dateStr);
                    dateStr = d.toLocaleString('hu-HU');
                }

                // Státusz színezése
                let statusColor = "var(--text-main)";
                if (file.status === "Feldolgozva") statusColor = "var(--success)";
                else if (file.status === "Hiba") statusColor = "var(--danger)";
                else if (file.status === "Új") statusColor = "var(--accent)";

                tr.innerHTML = `
                    <td><strong>${file.fileName || '-'}</strong></td>
                    <td><div style="max-width: 300px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;" title="${file.path || ''}">${file.path || '-'}</div></td>
                    <td>${file.size ? file.size.toLocaleString('hu-HU') : '-'}</td>
                    <td>${dateStr}</td>
                    <td style="color: ${statusColor}; font-weight: 500;">${file.status || '-'}</td>
                `;
                filesTableBody.appendChild(tr);
            });

            logMessage(`${data.length} PST fájl sikeresen betöltve.`, 'success');
            showToast(`${data.length} fájl rekord betöltve`, "success");

        } catch (e) {
            setSystemStatus('online', 'Rendszer Aktív');
            logMessage(`Hiba a fájlok betöltésekor: ${e.message}`, 'error');
            showToast("Sikertelen betöltés", "error");
            filesTableBody.innerHTML = '<tr><td colspan="5" class="empty-state" style="color: var(--danger);">Hiba történt az adatok betöltése közben.</td></tr>';
        } finally {
            btnLoadFiles.disabled = false;
            btnLoadFiles.innerHTML = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="18" height="18"><path d="M21 2v6h-6"></path><path d="M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"></path><path d="M3 3v6h6"></path></svg> PST Fájlok Frissítése`;
        }
    });

});
