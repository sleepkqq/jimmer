import assert from "node:assert/strict";
import { readFile, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";

// Opt-in network check. The original 26 audit queries are unchanged; four mapping
// holdouts were added before the first run of the repository-backed implementation.
const cases = JSON.parse(await readFile(new URL("./retrieval-cases.json", import.meta.url), "utf8"));
const client = new Client({ name: "jimmer-live-retrieval-check", version: "1.0.0" });
const results: object[] = [];
const urls = new Set<string>();
const report: Record<string, unknown> = { queries: results };
async function call(name: string, args: Record<string, unknown>) {
	const result = await client.callTool({ name, arguments: args });
	assert(!result.isError, JSON.stringify(result));
	return JSON.parse((result.content as { text: string }[])[0].text);
}
try {
	await client.connect(new StdioClientTransport({ command: process.execPath, args: [resolve("dist/bundle.js")], env: {
		...(process.env.JIMMER_DOCS_CACHE_DIR ? { JIMMER_DOCS_CACHE_DIR: process.env.JIMMER_DOCS_CACHE_DIR } : {}),
	} }));
	let revision = process.env.JIMMER_DOCS_EVAL_REF ?? "main";
	for (const item of cases) {
		const response = await call("jimmer_docs_search", { query: item.query, limit: 3, ref: revision });
		revision = response.revision;
		report.docsRevision = revision;
		const paths = response.results.map((result: { path: string }) => result.path);
		const rank = response.results.findIndex((result: { websiteUrl?: string }) => item.expected.some((path: string) => result.websiteUrl?.endsWith("/docs" + path))) + 1;
		for (const result of response.results) {
			if (result.websiteUrl) urls.add(result.websiteUrl);
			assert(result.matches[0].read.ref === revision);
			const read = await call("jimmer_docs_read", result.matches[0].read);
			assert.equal(read.revision, revision);
			assert(read.content.length > 0);
		}
		if (!item.expected.length) {
			assert.equal(response.results.length, 0, item.query);
			assert(response.hint);
		}
		const row = { ...item, rank: rank || null, paths };
		results.push(row);
		console.log(JSON.stringify(row));
	}
	const fidelity = [];
	for (const path of ["docs/query/base-query.mdx", "docs/mutation/insert-from-select.mdx", "docs/mutation/save-command/input-dto/null-handling.mdx"]) {
		let page = await call("jimmer_docs_read", { path, ref: revision, maxLines: 100 });
		let content = page.content;
		let pages = 1;
		while (page.nextRead) {
			page = await call("jimmer_docs_read", page.nextRead);
			assert.equal(page.revision, revision);
			content += page.content;
			assert(++pages < 100, "Continuation did not terminate");
		}
		const original = await fetch(`https://raw.githubusercontent.com/babyfish-ct/jimmer-doc/${revision}/${path}`);
		assert(original.ok);
		assert.equal(content, await original.text());
		fidelity.push({ path, pages, characters: content.length, identical: true });
	}
	report.fidelity = fidelity;
	const statuses = [];
	for (const url of urls) {
		const response = await fetch(url, { method: "HEAD" });
		statuses.push({ url, status: response.status });
		assert(response.ok, `${url}: ${response.status}`);
	}
	report.urls = statuses;
	const source = await call("jimmer_source_lookup", { symbol: "TriggerType", ref: process.env.JIMMER_SOURCE_EVAL_REF ?? "v0.12.2" });
	assert(source.results[0].path.endsWith("/event/TriggerType.java"));
	const trigger = await call("jimmer_docs_read", source.results[0].read);
	assert.match(trigger.content, /@Deprecated\s+TRANSACTION_ONLY/);
	assert.match(trigger.content, /@Deprecated\s+BOTH/);
	report.sourceRevision = source.revision;
	report.sourceCheck = "Both transaction trigger variants are deprecated in the pinned source.";
	for (const group of ["english", "holdout"]) {
		const subset = (results as { group: string; rank: number | null }[]).filter(row => row.group === group);
		report[group] = { tasks: subset.length, at1: subset.filter(row => row.rank === 1).length, at3: subset.filter(row => row.rank !== null).length };
	}
	console.log(JSON.stringify(report, null, 2));
} finally {
	await client.close();
	if (process.env.JIMMER_MCP_EVAL_OUTPUT) await writeFile(process.env.JIMMER_MCP_EVAL_OUTPUT, JSON.stringify(report, null, 2) + "\n");
}
