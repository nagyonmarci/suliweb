import React, { useState } from 'react';
import axios from 'axios';

function PdfForm() {
    const [templatePath, setTemplatePath] = useState('');
    const [formData, setFormData] = useState({});
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState('');

    const handleInputChange = (e) => {
        setFormData({
            ...formData,
            [e.target.name]: e.target.value
        });
    };

    const handleSubmit = async (e) => {
        e.preventDefault();
        setLoading(true);
        setError('');

        try {
            const response = await axios.post('http://localhost:8080/pdf/fill', {
                templatePath,
                formData
            }, {
                responseType: 'blob' // Fontos, hogy blobként kezeljük a választ
            });

            const url = window.URL.createObjectURL(new Blob([response.data]));
            const link = document.createElement('a');
            link.href = url;
            link.setAttribute('download', 'filled-form.pdf');
            document.body.appendChild(link);
            link.click();
        } catch (err) {
            setError('Hiba történt a PDF generálása közben.');
            console.error(err);
        } finally {
            setLoading(false);
        }
    };

    return (
        <div>
            <form onSubmit={handleSubmit}>
                <label>
                    Sablon útvonal:
                    <input
                        type="text"
                        value={templatePath}
                        onChange={(e) => setTemplatePath(e.target.value)}
                        required
                    />
                </label>
                {/* Itt adhatja meg a további űrlapmezők dinamikus hozzáadását */}
                <label>
                    Mező adat:
                    <input
                        type="text"
                        name="fieldName"
                        onChange={handleInputChange}
                        required
                    />
                </label>
                <button type="submit" disabled={loading}>
                    PDF Kitöltése
                </button>
            </form>
            {loading && <p>Kérjük, várjon...</p>}
            {error && <p>{error}</p>}
        </div>
    );
}

export default PdfForm;
