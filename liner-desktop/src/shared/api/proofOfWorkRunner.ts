import PowWorker from "./proofOfWork.worker?worker&inline";
import {
  PowTimeoutError,
  solvePowChallenge,
  type PowChallenge,
  type PowSolution,
} from "./proofOfWork";

type WorkerReply =
  | { ok: true; solution: PowSolution }
  | { ok: false; timeout?: boolean; message: string };

class WorkerUnavailable extends Error {}

function solveInWorker(
  challenge: PowChallenge,
  signal?: AbortSignal,
): Promise<PowSolution> {
  if (signal?.aborted) {
    return Promise.reject(
      signal.reason ?? new DOMException("Aborted", "AbortError"),
    );
  }
  return new Promise((resolve, reject) => {
    let worker: Worker;
    try {
      worker = new PowWorker();
    } catch (error) {
      reject(new WorkerUnavailable(String(error)));
      return;
    }
    const onAbort = () => {
      worker.terminate();
      reject(signal?.reason ?? new DOMException("Aborted", "AbortError"));
    };
    const cleanup = () => {
      signal?.removeEventListener("abort", onAbort);
    };
    signal?.addEventListener("abort", onAbort, { once: true });
    worker.onmessage = (event: MessageEvent<WorkerReply>) => {
      cleanup();
      worker.terminate();
      if (event.data.ok) resolve(event.data.solution);
      else
        reject(
          event.data.timeout
            ? new PowTimeoutError()
            : new Error(event.data.message),
        );
    };
    worker.onerror = (event) => {
      cleanup();
      worker.terminate();
      reject(new WorkerUnavailable(event.message || "worker failed"));
    };
    worker.postMessage(challenge);
  });
}

export async function solvePow(
  challenge: PowChallenge,
  signal?: AbortSignal,
): Promise<PowSolution> {
  try {
    return await solveInWorker(challenge, signal);
  } catch (error) {
    if (error instanceof WorkerUnavailable)
      return solvePowChallenge(challenge, signal);
    throw error;
  }
}
