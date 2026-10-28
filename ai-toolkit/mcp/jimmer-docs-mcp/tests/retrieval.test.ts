import assert from "node:assert/strict";
import { execFile as exec } from "node:child_process";
import { mkdtemp, mkdir, readFile, rename, rm, symlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { promisify } from "node:util";
import { test } from "node:test";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import { DocsIndex, parseDocument, readRange } from "../src/docs-index.js";
import { Repository } from "../src/repository.js";

const execFile = promisify(exec);
const example = '---\r\ntitle: Examples\r\n---\r\nimport Shared from "./_shared/example.mdx";\r\nimport Panel from "@site/src/components/Panel";\r\n\r\n## First\r\n<TabItem value="java">\r\n```java\r\nList<Book> books;\r\nif (a < b && c > d) work();\r\n// Unicode: résumé 中文\r\n# not a Markdown heading inside code\r\n```\r\n</TabItem>\r\n\r\n## Later\r\nCall `refreshSnapshot` here. [Guide](./other).\r\n<Shared />\r\n';

test("MDX parsing preserves source, tabs, links, generics, imports, and code headings", () => {
	const document = parseDocument("docs/example.mdx", example, new Set(["docs/_shared/example.mdx", "src/components/Panel.tsx"]));
	assert.equal(document.text, example);
	assert.equal(document.title, "Examples");
	assert.equal(document.websiteUrl, "https://babyfish-ct.github.io/jimmer-doc/docs/example");
	assert.deepEqual(document.sections.map(section => section.heading), ["Examples", "First", "Later"]);
	assert.deepEqual(document.imports.map(item => item.path), ["docs/_shared/example.mdx", "src/components/Panel.tsx"]);
	assert.equal(parseDocument("docs/_shared/example.mdx", "Shared", new Set()).websiteUrl, undefined);
	assert.equal(parseDocument("docs/a/index.md", "Text", new Set()).websiteUrl, "https://babyfish-ct.github.io/jimmer-doc/docs/a/");
	let line: number | null = 1;
	let reconstructed = "";
	while (line) {
		const range = readRange(example, line, 3);
		reconstructed += range.content;
		line = range.nextStartLine;
	}
	assert.equal(reconstructed, example);
	assert.throws(() => readRange(example, 1000, 1), /Invalid line range/);
});

test("retrieval uses real late sections and deduplicates before the page limit", () => {
	const contents = new Map([
		["docs/long.md", "---\ntitle: Snapshots\n---\n" + Array.from({ length: 12 }, (_, i) => `## Section ${i}\nrefreshSnapshot takes a snapshot.\n`).join("")],
		["docs/other.md", "## Refresh\nrefreshSnapshot returns a revision.\n"],
		["docs/recovery.md", "## Recovery\nrefreshSnapshot retries failed requests.\n"],
		["docs/irrelevant.md", "## Ordinary guide\nSnapshots and revisions are useful.\n"],
	]);
	const index = new DocsIndex(contents, new Set(contents.keys()));
	const results = index.search("refreshSnapshot", 3);
	assert.equal(results.length, 3);
	assert.equal(new Set(results.map(result => result.document.path)).size, 3);
	assert(results.every(result => result.matches[0].snippet.includes("refreshSnapshot")));
	assert.deepEqual(index.search("Jimmer MissingUnpublishedFeature", 3), []);
	assert.deepEqual(index.search("Jimmer zxqv_nonexistent_orm_feature_8721", 3), []);
	assert.deepEqual(index.search("the and how", 3), []);
});

test("short page titles retain the parent topic instead of incidental mentions", () => {
	const contents = new Map([
		["docs/parsers/index.md", "---\ntitle: Input Parsers\n---\nIntroduction.\n"],
		["docs/parsers/output.md", "---\ntitle: Output\n---\n## Shape\nThe generated result contains the parsed value.\n"],
		["docs/writers.md", "---\ntitle: Writers\n---\n## Output\nUnlike input parsers, writers generate text.\n"],
	]);
	const result = new DocsIndex(contents, new Set(contents.keys())).search("input parsers output", 1)[0];
	assert.equal(result.document.path, "docs/parsers/output.md");
	assert.deepEqual(result.document.breadcrumbs, ["Input Parsers"]);
});

async function fixture(t: { after: (fn: () => Promise<void>) => void }) {
	const root = await mkdtemp(join(process.env.TMPDIR ?? tmpdir(), "jimmer-mcp-test-"));
	t.after(() => rm(root, { recursive: true, force: true }));
	const remote = join(root, "upstream");
	await mkdir(remote);
	const git = (...args: string[]) => execFile("git", ["-C", remote, ...args], { env: { ...process.env, GIT_TERMINAL_PROMPT: "0" } });
	await git("init", "--quiet", "--initial-branch=main");
	await git("config", "user.name", "MCP test");
	await git("config", "user.email", "mcp@example.invalid");
	await mkdir(join(remote, "docs/_shared"), { recursive: true });
	await writeFile(join(remote, "docs/example.mdx"), example);
	await writeFile(join(remote, "docs/_shared/example.mdx"), "## Shared\nShared examples are part of retrieval.\n");
	await symlink("/etc/passwd", join(remote, "docs/escape.md"));
	await git("add", ".");
	await git("commit", "--quiet", "-m", "fixture");
	return { root, remote, git, cache: join(root, "cache") };
}

test("repository refresh, pinned reads, restart-offline fallback, and recovery", async t => {
	const f = await fixture(t);
	let now = Date.now();
	const repo = new Repository("docs", f.remote, f.cache, 1000, () => now);
	const [first, concurrent] = await Promise.all([repo.snapshot(), repo.snapshot()]);
	assert.equal(first.revision, concurrent.revision);
	assert.equal((await repo.contents(first.revision, ["docs/example.mdx"])).get("docs/example.mdx"), example);
	await assert.rejects(() => repo.contents(first.revision, ["docs/escape.md"]), /Not a readable/);
	await assert.rejects(() => repo.contents(first.revision, ["../../etc/passwd"]), /Not a readable/);
	await assert.rejects(() => repo.snapshot("--upload-pack=evil"), /Invalid ref/);
	await writeFile(join(f.remote, "docs/example.mdx"), example + "New documentation.\n");
	await f.git("commit", "-am", "update", "--quiet");
	assert.equal((await repo.snapshot()).revision, first.revision);
	now += 1001;
	const updated = await repo.snapshot();
	assert.notEqual(updated.revision, first.revision);
	assert.equal((await repo.snapshot(first.revision)).revision, first.revision);
	assert.equal((await repo.contents(first.revision, ["docs/example.mdx"])).get("docs/example.mdx"), example);
	await rename(f.remote, f.remote + "-offline");
	now += 1001;
	const restarted = new Repository("docs", f.remote, f.cache, 1000, () => now);
	const stale = await restarted.snapshot();
	assert.equal(stale.revision, updated.revision);
	assert.equal(stale.checkedAt, updated.checkedAt);
	assert.equal(stale.stale, true);
	assert.match(stale.warning!, /Refresh failed/);
	await rename(f.remote + "-offline", f.remote);
	assert.equal((await restarted.snapshot("main", true)).stale, false);
});

test("a failed initial load retries; an unowned directory is preserved", async t => {
	const f = await fixture(t);
	await rename(f.remote, f.remote + "-offline");
	const repo = new Repository("docs", f.remote, f.cache);
	await assert.rejects(() => repo.snapshot(), /Cannot load/);
	await rename(f.remote + "-offline", f.remote);
	assert.equal((await repo.snapshot()).stale, false);
	await mkdir(join(f.root, "other-cache/docs.git"), { recursive: true });
	await writeFile(join(f.root, "other-cache/docs.git/user-file"), "keep");
	await assert.rejects(() => new Repository("docs", f.remote, join(f.root, "other-cache")).snapshot(), /unowned/);
	assert.equal(await readFile(join(f.root, "other-cache/docs.git/user-file"), "utf8"), "keep");
});

test("real stdio MCP lists tools, reads losslessly, follows cursors, and flags errors", async t => {
	const f = await fixture(t);
	const path = "project/example/src/main/java/org/example/SampleMode.java";
	await mkdir(join(f.remote, "project/example/src/main/java/org/example"), { recursive: true });
	await writeFile(join(f.remote, path), "package org.example;\npublic enum SampleMode { CURRENT, @Deprecated OLD }\n");
	await f.git("add", ".");
	await f.git("commit", "--quiet", "-m", "source fixture");
	await f.git("tag", "v0.0.1");
	const transport = new StdioClientTransport({ command: process.execPath, args: [resolve("dist/bundle.js")], env: {
		JIMMER_DOCS_CACHE_DIR: f.cache,
		GIT_CONFIG_COUNT: "2",
		GIT_CONFIG_KEY_0: `url.file://${f.remote}.insteadOf`, GIT_CONFIG_VALUE_0: "https://github.com/babyfish-ct/jimmer-doc.git",
		GIT_CONFIG_KEY_1: `url.file://${f.remote}.insteadOf`, GIT_CONFIG_VALUE_1: "https://github.com/babyfish-ct/jimmer.git",
	} });
	const client = new Client({ name: "retrieval-test", version: "1.0.0" });
	await client.connect(transport);
	t.after(() => client.close());
	assert.deepEqual((await client.listTools()).tools.map(tool => tool.name).sort(), ["jimmer_docs_read", "jimmer_docs_search", "jimmer_source_lookup"]);
	async function call(name: string, args: Record<string, unknown>) {
		const result = await client.callTool({ name, arguments: args });
		assert(!result.isError, JSON.stringify(result));
		return JSON.parse((result.content as { text: string }[])[0].text);
	}
	const search = await call("jimmer_docs_search", { query: "refreshSnapshot" });
	assert.equal(search.results[0].path, "docs/example.mdx");
	assert.equal(search.revision.length, 40);
	const relevant = await call("jimmer_docs_read", search.results[0].matches[0].read);
	assert.match(relevant.content, /refreshSnapshot/);
	let page = await call("jimmer_docs_read", { path: "docs/example.mdx", ref: search.revision, maxLines: 4 });
	let content = page.content;
	while (page.nextRead) { page = await call("jimmer_docs_read", page.nextRead); content += page.content; }
	assert.equal(content, example);
	const source = await call("jimmer_source_lookup", { symbol: "org.example.SampleMode", ref: "v0.0.1" });
	assert.equal(source.results[0].path, path);
	assert.match((await call("jimmer_docs_read", source.results[0].read)).content, /@Deprecated OLD/);
	for (const args of [{ query: " " }, { query: "example", limit: 1.5 }]) {
		assert.equal((await client.callTool({ name: "jimmer_docs_search", arguments: args })).isError, true);
	}
	assert.equal((await client.callTool({ name: "jimmer_docs_read", arguments: { path: "../../etc/passwd" } })).isError, true);
});
