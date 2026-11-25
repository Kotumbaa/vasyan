import fsp from "node:fs/promises";

const RETRYABLE_RENAME_CODES = new Set(["EBUSY", "EPERM", "EACCES"]);
const MAX_RENAME_ATTEMPTS = 4;

/**
 * Atomically writes `data` to `target` via a temp file + rename, retrying on
 * transient Windows file-lock errors (`EBUSY`/`EPERM`) and falling back to
 * direct write if the rename remains blocked.
 */
export async function writeFileAtomic(
  target: string,
  data: string | Uint8Array,
): Promise<void> {
  const temp = `${target}.tmp`;
  await fsp.writeFile(temp, data);
  for (let attempt = 0; attempt < MAX_RENAME_ATTEMPTS; attempt++) {
    try {
      await fsp.rename(temp, target);
      return;
    } catch (err) {
      const code = (err as NodeJS.ErrnoException | null)?.code;
      if (!code || !RETRYABLE_RENAME_CODES.has(code)) {
        await fsp.rm(temp, { force: true }).catch(() => undefined);
        throw err;
      }
      if (attempt < MAX_RENAME_ATTEMPTS - 1) {
        await new Promise((resolve) => setTimeout(resolve, 15 * (attempt + 1)));
      }
    }
  }
  await fsp.writeFile(target, data);
  await fsp.rm(temp, { force: true }).catch(() => undefined);
}
