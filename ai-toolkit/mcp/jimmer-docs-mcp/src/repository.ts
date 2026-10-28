import { execFile } from "node:child_process";
import { createHash, randomUUID } from "node:crypto";
import { mkdir, mkdtemp, readFile, rename, rm, writeFile } from "node:fs/promises";
import { dirname, join } from "node:path";

const SHA = /^[a-f0-9]{40}$/;
const MAX_OUTPUT = 64 * 1024 * 1024;

export interface Snapshot {
	ref: string;
	revision: string;
	commitDate: string;
	checkedAt: string | null;
	stale: boolean;
	warning?: string;
}

export interface RepoFile {
	path: string;
	object: string;
}

function git(directory: string, args: string[], input?: string): Promise<Buffer> {
	return new Promise((resolve, reject) => {
		const child = execFile("git", ["-c", "core.hooksPath=/dev/null", "-c", "gc.auto=0", "-C", directory, ...args], {
			encoding: "buffer", maxBuffer: MAX_OUTPUT, timeout: 45_000,
			env: { ...process.env, GIT_TERMINAL_PROMPT: "0" },
		}, (error, stdout) => {
			if (error) {
				reject(new Error(error.code === "ENOENT" ? "Git is required but was not found on PATH." :
					`git ${args[0]} failed (${error.killed ? "timeout" : error.code}). Check network access and the repository ref.`));
			} else resolve(stdout);
		});
		child.stdin?.on("error", reject);
		child.stdin?.end(input);
	});
}

export function validateRef(ref: string): void {
	if (!/^[a-zA-Z0-9][a-zA-Z0-9._/-]{0,127}$/.test(ref) || ref.includes("..") || ref.includes("//") || ref.endsWith("/")) {
		throw new Error("Invalid ref: use a branch, tag, or full commit SHA.");
	}
}

/** A private bare cache: never checks out files or modifies a user's working tree. */
export class Repository {
	readonly directory: string;
	private ready?: Promise<void>;
	private pending = new Map<string, Promise<Snapshot>>();
	private snapshots = new Map<string, { value: Snapshot; retryAt: number }>();
	private trees = new Map<string, Promise<RepoFile[]>>();

	constructor(
		readonly id: string,
		readonly url: string,
		cacheDirectory: string,
		private ttl = 60 * 60 * 1000,
		private now: () => number = Date.now,
	) {
		this.directory = join(cacheDirectory, `${id}.git`);
	}

	private async initialize(): Promise<void> {
		if (!this.ready) {
			this.ready = (async () => {
				try {
					const owner = await readFile(join(this.directory, "mcp-origin"), "utf8");
					if (owner !== this.url) throw new Error("Repository cache belongs to another source.");
					return;
				} catch (error) {
					if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error;
				}
				await mkdir(dirname(this.directory), { recursive: true });
				const temporary = await mkdtemp(this.directory + ".tmp-");
				try {
					await git(temporary, ["init", "--bare", "--quiet"]);
					await writeFile(join(temporary, "mcp-origin"), this.url);
					try {
						await rename(temporary, this.directory);
					} catch (error) {
						if (!["EEXIST", "ENOTEMPTY"].includes((error as NodeJS.ErrnoException).code ?? "")) throw error;
						if (await readFile(join(this.directory, "mcp-origin"), "utf8").catch(() => "") !== this.url) {
							throw new Error("Refusing to use an unowned directory as an MCP cache.");
						}
					}
				} finally { await rm(temporary, { recursive: true, force: true }); }
			})();
			this.ready.catch(() => { this.ready = undefined; });
		}
		return this.ready;
	}

	async snapshot(ref = "main", refresh = false): Promise<Snapshot> {
		validateRef(ref);
		const cached = this.snapshots.get(ref);
		if (!refresh && cached && this.now() < cached.retryAt) return cached.value;
		const pending = this.pending.get(ref);
		if (pending) return pending;
		const loading = this.load(ref, refresh).finally(() => this.pending.delete(ref));
		this.pending.set(ref, loading);
		return loading;
	}

	private async load(ref: string, refresh: boolean): Promise<Snapshot> {
		await this.initialize();
		if (SHA.test(ref)) {
			try {
				const commitDate = (await git(this.directory, ["show", "-s", "--format=%cI", `${ref}^{commit}`])).toString().trim();
				return { ref, revision: ref, commitDate, checkedAt: null, stale: false };
			} catch { /* Fetch an immutable revision only if it is not already cached. */ }
		}
		const key = createHash("sha256").update(ref).digest("hex");
		const metadata = join(this.directory, `mcp-${key}.json`);
		let previous: Snapshot | undefined;
		try {
			const value = JSON.parse(await readFile(metadata, "utf8"));
			if (value.ref === ref && SHA.test(value.revision) && Number.isFinite(Date.parse(value.checkedAt)) && typeof value.commitDate === "string") {
				previous = { ...value, stale: false };
			}
		} catch (error) {
			if ((error as NodeJS.ErrnoException).code !== "ENOENT" && !(error instanceof SyntaxError)) throw error;
		}
		let value: Snapshot;
		if (!refresh && previous && this.now() - Date.parse(previous.checkedAt!) < this.ttl) {
			value = previous;
		} else {
			try {
				const destination = `refs/mcp/${key}`;
				await git(this.directory, ["fetch", "--quiet", "--depth=1", "--no-tags", "--no-recurse-submodules", "--no-write-fetch-head", this.url, `+${ref}:${destination}`]);
				const revision = (await git(this.directory, ["rev-parse", "--verify", `${destination}^{commit}`])).toString().trim();
				const commitDate = (await git(this.directory, ["show", "-s", "--format=%cI", revision])).toString().trim();
				value = { ref, revision, commitDate, checkedAt: new Date(this.now()).toISOString(), stale: false };
				const temporary = `${metadata}.${randomUUID()}.tmp`;
				try {
					await writeFile(temporary, JSON.stringify(value));
					await rename(temporary, metadata);
				} finally { await rm(temporary, { force: true }); }
			} catch (error) {
				if (!previous) throw new Error(`Cannot load ${this.id}@${ref}: ${(error as Error).message}`);
				value = { ...previous, stale: true, warning: `Refresh failed; serving the last verified snapshot. ${(error as Error).message}` };
			}
		}
		// Failed refreshes retry shortly; neither rejected promises nor empty indexes are cached.
		this.snapshots.set(ref, { value, retryAt: value.stale ? this.now() + 30_000 : Date.parse(value.checkedAt!) + this.ttl });
		if (this.snapshots.size > 8) this.snapshots.delete(this.snapshots.keys().next().value!);
		return value;
	}

	async files(revision: string): Promise<RepoFile[]> {
		if (!SHA.test(revision)) throw new Error("Expected a full commit SHA.");
		await this.initialize();
		let pending = this.trees.get(revision);
		if (!pending) {
			pending = git(this.directory, ["ls-tree", "-rz", "--full-tree", revision]).then(output =>
				output.toString().split("\0").flatMap(entry => {
					const match = /^(100644|100755) blob ([a-f0-9]{40})\t([^\r\n\0]+)$/.exec(entry);
					return match ? [{ object: match[2], path: match[3] }] : [];
				}));
			this.trees.set(revision, pending);
			pending.catch(() => this.trees.delete(revision));
			if (this.trees.size > 8) this.trees.delete(this.trees.keys().next().value!);
		}
		return pending;
	}

	async contents(revision: string, paths: string[]): Promise<Map<string, string>> {
		const files = new Map((await this.files(revision)).map(file => [file.path, file]));
		const selected = paths.map(path => {
			const file = files.get(path);
			if (!file || !/\.(mdx?|java|kt|kts|tsx?|jsx?|json|ya?ml|properties|g4)$/.test(path)) {
				throw new Error(`Not a readable source file in this snapshot: ${path}`);
			}
			return file;
		});
		if (!selected.length) return new Map();
		// Git's length-prefixed batch protocol preserves Unicode, angle brackets and line endings.
		const buffer = await git(this.directory, ["cat-file", "--batch"], selected.map(file => file.object).join("\n") + "\n");
		const result = new Map<string, string>();
		let offset = 0;
		for (const file of selected) {
			const end = buffer.indexOf(10, offset);
			const header = /^([a-f0-9]{40}) blob (\d+)$/.exec(buffer.subarray(offset, end).toString());
			const size = Number(header?.[2]);
			if (end < 0 || header?.[1] !== file.object || !Number.isSafeInteger(size) || size > 2 * 1024 * 1024 || end + size + 1 >= buffer.length || buffer[end + size + 1] !== 10) {
				throw new Error("Invalid Git object response or source file larger than 2 MiB.");
			}
			result.set(file.path, buffer.subarray(end + 1, end + 1 + size).toString("utf8"));
			offset = end + size + 2;
		}
		return result;
	}
}
