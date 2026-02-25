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

    btnLoadEmails.addEventListener('click', async () => {
        logMessage("Emailek betöltése az adatbázisból...", 'info');
        btnLoadEmails.disabled = true;
        btnLoadEmails.innerHTML = '<span class="status-dot busy"></span> Töltés...';

        try {
            // A fetch a JSON adatra vár, így közvetlen fetch-t használunk, 
            // hogy ne a globális apiCall() kezelje (ami .text()-et vár alapból)
            setSystemStatus('busy', 'Adatbázis lekérdezése...');
            const response = await fetch('/api/emails');

            if (!response.ok) throw new Error(`HTTP ${response.status}`);
            const data = await response.json();

            setSystemStatus('online', 'Rendszer Aktív');

            // Táblázat feltöltése
            emailsTableBody.innerHTML = '';

            if (!data || data.length === 0) {
                emailsTableBody.innerHTML = '<tr><td colspan="5" class="empty-state">Nincs megjeleníthető email az adatbázisban.</td></tr>';
                showToast("Nincs adat az adatbázisban", "info");
                return;
            }

            // Utolsó 100 elem mutatása, hogy ne fagyjon ki a böngésző ha túl sokat kap
            const displayData = data.slice(-100).reverse();

            displayData.forEach(email => {
                const tr = document.createElement('tr');

                // Formázott dátum
                let dateStr = email.receivedTime || '-';
                if (dateStr !== '-') {
                    const d = new Date(dateStr);
                    dateStr = d.toLocaleString('hu-HU');
                }

                tr.innerHTML = `
                    <td style="white-space: nowrap;">${dateStr}</td>
                    <td><div style="max-width: 200px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;" title="${email.senderName || ''} &lt;${email.senderEmailAddress || ''}&gt;">${email.senderName || email.senderEmailAddress || '-'}</div></td>
                    <td><div style="max-width: 300px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;" title="${email.subject || ''}"><strong>${email.subject || '(Nincs Tárgy)'}</strong></div></td>
                    <td><div style="max-width: 150px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;" title="${email.pstFileName || ''}">${email.pstFileName || '-'}</div></td>
                    <td><div style="max-width: 200px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;" title="${email.folderPath || ''}">${email.folderPath || '-'}</div></td>
                `;
                emailsTableBody.appendChild(tr);
            });

            logMessage(`${data.length} email sikeresen betöltve. (Utolsó 100 megjelenítve)`, 'success');
            showToast(`${data.length} rekord betöltve`, "success");

        } catch (e) {
            setSystemStatus('online', 'Rendszer Aktív');
            logMessage(`Hiba az emailek betöltésekor: ${e.message}`, 'error');
            showToast("Sikertelen betöltés", "error");
            emailsTableBody.innerHTML = '<tr><td colspan="5" class="empty-state" style="color: var(--danger);">Hiba történt az adatok betöltése közben.</td></tr>';
        } finally {
            btnLoadEmails.disabled = false;
            btnLoadEmails.innerHTML = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="18" height="18"><path d="M21 2v6h-6"></path><path d="M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"></path><path d="M3 3v6h6"></path></svg> Adatok Frissítése`;
        }
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
