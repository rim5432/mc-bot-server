"""Capability matrix and QA convergence package.

SQLite-backed storage for the player-behavior capability matrix,
QA test cases, and test result receipts. Designed for multi-agent
concurrent writes (WAL mode, short transactions, busy-timeout
retries) so QA agents, test runners, and human operators can update
state without stepping on each other.
"""
