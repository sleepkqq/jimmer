# Transfer-focused refinement

This follow-up changes domain guidance, not the original benchmark prompts or assertions. It compares the previous **17-skill** working tree with a new candidate, rather than reusing the much weaker original 14-skill baseline to inflate the apparent gain.

## Practices used

Research read before this revision:

- [Anthropic: effective context engineering](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents): minimal high-signal context, clear structure, the right level of abstraction, a few canonical examples instead of enumerating every edge case.
- [Anthropic: demystifying agent evals](https://www.anthropic.com/engineering/demystifying-evals-for-ai-agents): inspect actual outcomes, distinguish capability from regression tests, retain trials and audit graders for hidden requirements.
- [OpenAI: evaluation best practices](https://developers.openai.com/api/docs/guides/evaluation-best-practices): task-specific evaluation, unseen cases, multiple trials, source-grounded criteria and controlling judge bias.
- [Agent Skills: optimizing descriptions](https://agentskills.io/skill-creation/optimizing-descriptions): separate activation from output quality, use positive and near-miss tasks, and keep validation examples out of the optimization loop.

Applied to skills:

1. **Choose from the user's contract.** Save modes are organized around identity, allowed mutations and required results. A desire to retrieve an ID is not permission to update a row.
2. **Keep conditions beside the operation.** DML tables connect a choice to its conflict/returning/execution-plan consequences. Examples are conditional recipes, not claims that compiling an API guarantees a particular SQL plan.
3. **Trace state end to end.** DTO guidance follows JSON presence → generated loaded state → associated save policy → database effect. Cache guidance follows committed changes → invalidation → acknowledgement and recovery.
4. **Verify examples outside the model.** The upsert example executes on native and materialized plans; it was not added merely as a phrase for a grader to find.

No task-specific answer templates, benchmark IDs, expected grades or evaluation phrases were added to skills. The existing test prompts, word limits, model/variant, system prompt and criteria remain fixed. This experiment does not claim to measure automatic activation or progressive disclosure.

## Separation protocol

- Snapshot the previous skills and record all file hashes before edits.
- Have a separate agent author a transfer suite using public sources, without access to toolkit skills, earlier tasks or answers.
- Keep that suite's content hidden from the implementing session until the candidate skill snapshot is frozen. The sealed suite SHA256 is `2c1b96bbae963c11370bfb8a3a53d3ae06ef9f8ed980b9b0dc9434999a278bda`.
- Run both snapshots against both suites with the same model. Never expose assertions to the answering model. Retain every trial rather than selecting the best repetition.
- After unsealing, do not revise skills against transfer answers. Any later change prompted by them needs another independent validation set.

Original task file SHA256: `41d4079d22e07c3de582d674af745674c0faab355ce180b55715f4a6121e4fd5`.

## Source-driven discovery during executable validation

The first conditional-upsert smoke passed the native H2 plan but failed under the materialized fallback with `Cannot resolve the root table Warehouse:withBaseTableOwner`. Its condition referenced a property of a direct `select(entityTable)` exported source. Replacing that source with explicit scalar `addSelect` columns, as supported by upstream's materialized upsert tests, preserved the condition and passed both plans. The skill now provides that portable example and explains the source-accessor distinction. No upstream code was changed.

This is a concrete benefit of execution checks: neither a plausible answer nor a successful compile revealed the fallback problem.

## Transfer-suite audit before scoring

Independently generated tasks still need inspection. The sealed tasks omit some setup details: the upsert task does not name its dialect/ID generator/unique metadata, and the migration task does not name the existing soft-delete column. Accept explicit, valid prerequisites or clearly labeled illustrative assumptions; do not reward an unconditional guarantee with missing prerequisites. The cache assertion's phrase “cache/transaction mechanism” is not authority to recommend deprecated triggers: the current source-backed BinLog invalidation path is a valid interpretation. Reference notes are discovery hints, not a substitute for validating API paths in source.

These limitations are recorded before inspecting transfer answers. Frozen tasks and assertions are retained as written; a score must not be silently made easier or described as complete behavioral certification.

## Outcome

The [full follow-up report](../tests/skill-evals/ROUND2.md) retains every answer and failure. Fresh masked grading gives 52/64 → 54/64 on the original suite and 45/64 → 48/64 on transfer. Byte-identical control prompts also fluctuated, and two changed-context scenarios regressed. These results support neither a universal improvement claim nor 95% reliability. The executable upsert/DTO checks establish concrete example correctness; further prompt revisions require fresh independent validation rather than optimizing against the exposed transfer cases.
