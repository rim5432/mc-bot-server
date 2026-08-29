# qa-results

Machine-written artifacts of the external QA-run framework
(`qa_flow.py`-shaped sessions that plan and execute verification
rounds against this repo). Content is run data, not repository
prose: entries are predominantly Chinese because the delivery
consumer (the Lark document pipeline) works in Chinese. This
directory is the one deliberate data lane exempt from the
repository English-only markdown rule - treat it like the
byte-fidelity registry keys, not like documentation.

Do not hand-edit `qa-run.json`: the framework normalizes and
re-validates it, and the `change_ledger` records every mutation.
One-shot mutation scripts do not belong here; the durable history
is the JSON plus the repository commits it verifies.
