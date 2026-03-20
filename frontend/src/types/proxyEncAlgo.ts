// Shadowsocks 加密算法枚举
export enum ProxyEncAlgo {
  AES_256_GCM = 'aes_256_gcm',
  AES_128_GCM = 'aes_128_gcm',
  CHACHA20_IETF_POLY1305 = 'chacha20_ietf_poly1305',
  BLAKE3_2022_AES_128_GCM = '2022-blake3-aes-128-gcm'
}

// 加密算法标签映射
export const PROXY_ENC_ALGO_LABELS: Record<ProxyEncAlgo, string> = {
  [ProxyEncAlgo.AES_256_GCM]: 'AES-256-GCM',
  [ProxyEncAlgo.AES_128_GCM]: 'AES-128-GCM',
  [ProxyEncAlgo.CHACHA20_IETF_POLY1305]: 'ChaCha20-IETF-Poly1305',
  [ProxyEncAlgo.BLAKE3_2022_AES_128_GCM]: '2022-BLAKE3-AES-128-GCM'
};

export const PROXY_ENC_ALGO_OPTIONS = [
  { value: ProxyEncAlgo.AES_256_GCM, label: PROXY_ENC_ALGO_LABELS[ProxyEncAlgo.AES_256_GCM] },
  { value: ProxyEncAlgo.AES_128_GCM, label: PROXY_ENC_ALGO_LABELS[ProxyEncAlgo.AES_128_GCM] },
  { value: ProxyEncAlgo.CHACHA20_IETF_POLY1305, label: PROXY_ENC_ALGO_LABELS[ProxyEncAlgo.CHACHA20_IETF_POLY1305] },
  { value: ProxyEncAlgo.BLAKE3_2022_AES_128_GCM, label: PROXY_ENC_ALGO_LABELS[ProxyEncAlgo.BLAKE3_2022_AES_128_GCM] },
];
