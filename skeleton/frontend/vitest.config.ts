import { defineConfig } from 'vitest/config';

// Limit parallel workers: the TestBed-based property tests are memory-heavy, and
// unbounded workers run out of memory on machines with a few GB free.
export default defineConfig({
  test: {
    maxWorkers: 2,
  },
});
