/**
 * 格式化字节数为可读的字符串
 * @param bytes 字节数
 * @param decimals 小数位数，默认为2
 * @returns 格式化后的字符串，如 "1.23 KB", "456 B", "2.34 MB"
 */
export const formatBytes = (bytes: number, decimals: number = 2): string => {
  if (bytes === 0) return '0 B';
  
  const k = 1024;
  const dm = decimals < 0 ? 0 : decimals;
  const sizes = ['B', 'KB', 'MB', 'GB', 'TB', 'PB', 'EB', 'ZB', 'YB'];
  
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  
  return parseFloat((bytes / Math.pow(k, i)).toFixed(dm)) + ' ' + sizes[i];
};

/**
 * 格式化数字为可读的字符串（带千分位分隔符）
 * @param num 数字
 * @returns 格式化后的字符串，如 "1,234,567"
 */
export const formatNumber = (num: number): string => {
  return num.toLocaleString();
};