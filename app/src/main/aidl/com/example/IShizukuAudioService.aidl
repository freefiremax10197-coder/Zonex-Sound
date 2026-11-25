package com.example;

interface IShizukuAudioService {
    String getShellIdentity();
    String runAudioDiagnostic();
    String enableMicTransferRouting();
    String disableMicTransferRouting();
    void destroy();
}
