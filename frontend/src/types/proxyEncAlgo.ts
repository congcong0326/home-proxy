// Shadowsocks 加密算法枚举
export enum ProxyEncAlgo {
  AES_256_GCM = 'aes_256_gcm',
  AES_128_GCM = 'aes_128_gcm',
  CHACHA20_IETF_POLY1305 = 'chacha20_ietf_poly1305'
}

// 加密算法标签映射
export const PROXY_ENC_ALGO_LABELS: Record<ProxyEncAlgo, string> = {
  [ProxyEncAlgo.AES_256_GCM]: 'AES-256-GCM',
  [ProxyEncAlgo.AES_128_GCM]: 'AES-128-GCM',
  [ProxyEncAlgo.CHACHA20_IETF_POLY1305]: 'ChaCha20-IETF-Poly1305'
};