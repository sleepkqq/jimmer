import { posix } from "node:path";

export interface Section {
	heading: string;
	startLine: number;
	endLine: number;
	text: string;
}

export interface Document {
	path: string;
	title: string;
	breadcrumbs: string[];
	text: string;
	websiteUrl?: string;
	imports: { specifier: string; path?: string }[];
	sections: Section[];
}

export const sourceLines = (text: string): string[] => text.split(/(?<=\n)/);

function field(frontmatter: string, name: string): string | undefined {
	const value = new RegExp(`^${name}:\\s*(.+)$`, "m").exec(frontmatter)?.[1].trim();
	if (!value) return undefined;
	if (value.startsWith('"') && value.endsWith('"')) {
		try { return JSON.parse(value); } catch { return value.slice(1, -1); }
	}
	return value.replace(/^'(.*)'$/, "$1").replace(/''/g, "'");
}

export function parseDocument(path: string, text: string, files: Set<string>): Document {
	const lines = sourceLines(text);
	const frontmatterEnd = lines[0]?.trim() === "---" ? lines.findIndex((line, i) => i > 0 && line.trim() === "---") : -1;
	const frontmatter = frontmatterEnd > 0 ? lines.slice(1, frontmatterEnd).join("") : "";
	const title = field(frontmatter, "title") ?? posix.basename(path, posix.extname(path)).replace(/[-_]/g, " ");
	const sections: Section[] = [];
	const outsideCode: string[] = [];
	let heading = title;
	let start = Math.max(frontmatterEnd + 1, 0);
	let fence: string | undefined;
	for (let i = start; i < lines.length; i++) {
		const marker = /^ {0,3}(`{3,}|~{3,})/.exec(lines[i])?.[1];
		if (marker && !fence) { fence = marker; continue; }
		if (fence) {
			if (marker?.[0] === fence[0] && marker.length >= fence.length && lines[i].trim() === marker) fence = undefined;
			continue;
		}
		outsideCode.push(lines[i]);
		const match = /^ {0,3}#{1,6}\s+(.+?)\s*#*\s*$/.exec(lines[i]);
		if (match) {
			if (i > start) sections.push({ heading, startLine: start + 1, endLine: i, text: lines.slice(start, i).join("") });
			heading = match[1].replace(/\s*\{#[^}]+\}\s*$/, "");
			start = i;
		}
	}
	if (start < lines.length) sections.push({ heading, startLine: start + 1, endLine: lines.length, text: lines.slice(start).join("") });
	const imports = [...outsideCode.join("").matchAll(/^\s*import\s+[^;]+?\s+from\s+["']([^"']+)["']\s*;?/gm)].map(match => {
		const specifier = match[1];
		const base = specifier.startsWith("@site/") ? specifier.slice(6) : specifier.startsWith(".") ? posix.normalize(posix.join(posix.dirname(path), specifier)) : undefined;
		const resolved = base && [base, ...[".mdx", ".md", ".tsx", ".ts", ".jsx", ".js", "/index.tsx", "/index.ts"].map(ext => base + ext)].find(candidate => files.has(candidate));
		return { specifier, ...(resolved ? { path: resolved } : {}) };
	});
	let websiteUrl: string | undefined;
	if (/^(docs|faq)\//.test(path) && !path.split("/").some(part => part.startsWith("_"))) {
		const [root, ...parts] = path.split("/");
		const route = field(frontmatter, "slug") ?? parts.join("/").replace(/\.(md|mdx)$/, "").replace(/(^|\/)index$/, "$1");
		websiteUrl = `https://babyfish-ct.github.io/jimmer-doc/${root}/${route.replace(/^\//, "")}`;
	}
	return { path, title, breadcrumbs: path.split("/").slice(1, -1).map(part => part.replace(/[-_]/g, " ")), text, websiteUrl, imports, sections };
}

const STOP_WORDS = new Set("a an and are as at be by can do does for from how i in is it of on or that the this to use using was what when where which with would you your jimmer".split(" "));

function words(text: string): string[] {
	return (text.toLowerCase().match(/[\p{L}\p{N}_]+/gu) ?? []).filter(word => word.length > 1 && !STOP_WORDS.has(word));
}

function tokens(text: string): string[] {
	return [...words(text), ...words(text.replace(/([A-Z]+)([A-Z][a-z])/g, "$1 $2").replace(/([a-z\d])([A-Z])/g, "$1 $2").replace(/_/g, " "))];
}

interface IndexedSection {
	document: Document;
	section: Section;
	counts: Map<string, number>;
	title: Set<string>;
	heading: Set<string>;
	breadcrumbs: Set<string>;
	length: number;
}

/** Lexical retrieval over real sections; no model calls or query-specific synonym tables. */
export class DocsIndex {
	readonly documents: Map<string, Document>;
	private sections: IndexedSection[] = [];
	private frequency = new Map<string, number>();
	private averageLength: number;

	constructor(contents: Map<string, string>, files: Set<string>) {
		this.documents = new Map([...contents].map(([path, text]) => [path, parseDocument(path, text, files)]));
		for (const document of this.documents.values()) {
			const directories = document.path.split("/").slice(0, -1);
			document.breadcrumbs = directories.slice(1).map((part, i) => {
				const parent = directories.slice(0, i + 2).join("/") + "/index";
				return (this.documents.get(parent + ".mdx") ?? this.documents.get(parent + ".md"))?.title ?? part.replace(/[-_]/g, " ");
			});
			for (const section of document.sections) {
				const counts = new Map<string, number>();
				const terms = tokens(`${document.title} ${document.breadcrumbs.join(" ")} ${document.path} ${section.heading} ${section.text}`);
				for (const term of terms) counts.set(term, (counts.get(term) ?? 0) + 1);
				for (const term of counts.keys()) this.frequency.set(term, (this.frequency.get(term) ?? 0) + 1);
				this.sections.push({ document, section, counts, title: new Set(tokens(document.title)), heading: new Set(tokens(section.heading)), breadcrumbs: new Set(tokens(document.breadcrumbs.join(" "))), length: terms.length });
			}
		}
		this.averageLength = this.sections.reduce((sum, section) => sum + section.length, 0) / (this.sections.length || 1);
	}

	search(query: string, limit: number) {
		const terms = [...new Set(tokens(query))];
		// Explicit API identifiers are anchors, not optional noise next to generic words.
		const identifiers = [...query.matchAll(/[@#]?\b(?:[A-Za-z]+[a-z][A-Z][A-Za-z0-9]*|[A-Za-z][A-Za-z0-9]*_[A-Za-z0-9_]+)\b/g)].map(match => match[0].replace(/^[@#]/, "").toLowerCase());
		const scored = this.sections.flatMap(item => {
			if (identifiers.some(term => !item.counts.has(term))) return [];
			let score = 0;
			const matched = terms.filter(term => item.counts.has(term));
			for (const term of matched) {
				const tf = item.counts.get(term)!;
				const idf = Math.log(1 + (this.sections.length - this.frequency.get(term)! + 0.5) / (this.frequency.get(term)! + 0.5));
				score += idf * (tf * 2.2 / (tf + 1.2 * (0.25 + 0.75 * item.length / this.averageLength)) + (item.title.has(term) ? 2 : 0) + (item.heading.has(term) ? 2 : 0) + (item.breadcrumbs.has(term) ? 2 : 0));
			}
			return score > 0 ? [{ ...item, score: score * matched.length / terms.length, matchedTerms: matched }] : [];
		}).sort((a, b) => b.score - a.score || a.document.path.localeCompare(b.document.path) || a.section.startLine - b.section.startLine);
		// Apply the page limit after deduplication; preserve multiple matching sections per page.
		const selected = new Map<string, typeof scored>();
		for (const item of scored) {
			const existing = selected.get(item.document.path);
			if (existing) { if (existing.length < 3) existing.push(item); }
			else if (selected.size < limit) selected.set(item.document.path, [item]);
		}
		return [...selected.values()].map(items => ({ document: items[0].document, matches: items.map(({ section, matchedTerms }) => ({
			heading: section.heading, startLine: section.startLine, endLine: section.endLine, matchedTerms,
			snippet: section.text.slice(0, 1200), snippetOnly: true,
		})) }));
	}
}

export function readRange(text: string, startLine: number, maxLines: number) {
	const lines = sourceLines(text);
	if (!Number.isInteger(startLine) || startLine < 1 || startLine > lines.length || !Number.isInteger(maxLines) || maxLines < 1 || maxLines > 300) {
		throw new Error(`Invalid line range; file has ${lines.length} lines, maxLines must be 1–300.`);
	}
	let end = startLine - 1;
	let content = "";
	while (end < Math.min(lines.length, startLine - 1 + maxLines) && content.length + lines[end].length <= 24_000) content += lines[end++];
	if (end < startLine) throw new Error("This line exceeds the response budget; use the immutable source URL.");
	return { startLine, endLine: end, totalLines: lines.length, content, nextStartLine: end < lines.length ? end + 1 : null };
}
