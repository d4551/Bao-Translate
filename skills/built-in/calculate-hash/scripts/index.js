import { log } from '../../_shared/log.js';
import { fail } from '../../_shared/result.js';

async function digestMessage(message) {
  const msgUint8 = new TextEncoder().encode(message);
  const hashBuffer = await crypto.subtle.digest('SHA-1', msgUint8);
  const hashArray = Array.from(new Uint8Array(hashBuffer));
  const hashHex = hashArray
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('');
  return { result: hashHex };
}

window['ai_edge_gallery_get_result'] = async (data) => {
  try {
    const jsonData = JSON.parse(data);
    return JSON.stringify(await digestMessage(jsonData['text']));
  } catch (e) {
    fail('calculate-hash.topLevel', e);
    return JSON.stringify({ error: `Failed to calculate hash: ${e.message}` });
  }
};
