package com.hermespet;

// Jembatan ke proses ":llm" yang menjalankan AI lokal terisolasi.
interface ILlmService {
    // Muat model (cache by path) lalu hasilkan teks. null = gagal.
    String generate(String modelPath, String prompt);
}
