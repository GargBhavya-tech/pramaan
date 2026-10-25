# PRAMAAN evaluation report

> **Generated corpus.** These 1,600 rows come from `:harness:generateBenchmarkCorpus` (hand-chosen score distributions, fixed seed), not from
> people. The report shows the evaluation pipeline works; its zero error rates are properties of the generator. See `docs/evidence-report.md`, section 3.4.

1600 sessions. Thresholds are PROVISIONAL (not yet measured).

## Presentation attack detection (ISO/IEC 30107-3)

| PAIS | Level | N decided | APCER [95% CI] | 95% one-sided upper | APNRR [95% CI] | Note |
|---|---|---|---|---|---|---|
| mask-paper | L2 | 200 | 0.00% [0.00%, 1.83%] (0/200) | 1.49% | 0.00% [0.00%, 1.83%] (0/200) |  |
| print-matte-a4 | L1 | 200 | 0.00% [0.00%, 1.83%] (0/200) | 1.49% | 0.00% [0.00%, 1.83%] (0/200) |  |
| screen-phone-photo | L1 | 200 | 0.00% [0.00%, 1.83%] (0/200) | 1.49% | 0.00% [0.00%, 1.83%] (0/200) |  |
| screen-phone-video | L2 | 200 | 0.00% [0.00%, 1.83%] (0/200) | 1.49% | 0.00% [0.00%, 1.83%] (0/200) |  |

**Headline APCER (worst PAIS: mask-paper):** 0.00% [0.00%, 1.83%] (0/200)

**BPCER:** 0.00% [0.00%, 0.46%] (0/800)   **BPNRR:** 0.00% [0.00%, 0.46%] (0/800)

*ACER (deprecated by ISO/IEC 30107-3; literature comparison only): 0.00%*

**BPCER20** (BPCER at measured worst-PAIS APCER <= 5%): 0.00%   **BPCER100** (<= 1%): 0.00%

## Fairness (bona fide presentations)

### eyewear

| Group | BPCER [95% CI] | BPNRR [95% CI] | Note |
|---|---|---|---|
| glasses | 0.00% [0.00%, 1.37%] (0/268) | 0.00% [0.00%, 1.37%] (0/268) |  |
| none | 0.00% [0.00%, 0.69%] (0/532) | 0.00% [0.00%, 0.69%] (0/532) |  |

Differential: undefined (a group has no errors or no data); compare the intervals instead.

### lighting

| Group | BPCER [95% CI] | BPNRR [95% CI] | Note |
|---|---|---|---|
| backlit | 0.00% [0.00%, 1.76%] (0/208) | 0.00% [0.00%, 1.76%] (0/208) |  |
| dim | 0.00% [0.00%, 1.92%] (0/190) | 0.00% [0.00%, 1.92%] (0/190) |  |
| fluorescent | 0.00% [0.00%, 1.65%] (0/222) | 0.00% [0.00%, 1.65%] (0/222) |  |
| good | 0.00% [0.00%, 2.03%] (0/180) | 0.00% [0.00%, 2.03%] (0/180) |  |

Differential: undefined (a group has no errors or no data); compare the intervals instead.

### person

| Group | BPCER [95% CI] | BPNRR [95% CI] | Note |
|---|---|---|---|
| P000 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P001 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P002 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P003 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P004 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P005 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P006 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P007 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P008 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P009 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P010 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P011 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P012 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P013 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P014 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P015 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P016 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P017 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P018 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P019 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P020 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P021 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P022 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P023 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P024 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P025 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P026 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P027 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P028 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P029 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P030 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P031 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P032 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P033 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P034 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P035 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P036 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P037 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P038 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P039 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P040 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P041 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P042 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P043 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P044 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P045 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P046 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P047 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P048 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P049 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P050 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P051 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P052 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P053 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P054 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P055 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P056 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P057 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P058 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P059 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P060 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P061 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P062 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P063 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P064 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P065 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P066 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P067 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P068 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P069 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P070 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P071 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P072 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P073 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P074 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P075 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P076 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P077 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P078 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P079 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P080 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P081 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P082 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P083 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P084 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P085 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P086 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P087 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P088 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P089 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P090 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P091 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P092 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P093 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P094 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P095 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P096 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P097 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P098 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P099 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P100 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P101 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P102 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P103 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P104 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P105 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P106 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P107 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P108 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P109 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P110 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P111 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P112 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P113 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P114 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P115 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P116 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P117 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P118 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P119 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P120 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P121 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P122 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P123 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P124 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P125 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P126 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P127 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P128 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P129 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P130 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P131 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P132 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P133 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P134 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P135 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P136 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P137 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P138 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P139 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P140 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P141 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P142 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P143 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P144 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P145 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P146 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P147 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P148 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P149 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P150 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P151 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P152 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P153 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P154 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P155 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P156 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P157 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P158 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P159 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P160 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P161 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P162 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P163 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P164 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P165 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P166 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P167 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P168 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P169 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P170 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P171 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P172 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P173 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P174 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P175 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P176 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P177 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P178 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P179 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P180 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P181 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P182 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P183 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P184 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P185 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P186 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P187 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P188 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P189 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P190 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P191 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P192 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P193 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P194 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P195 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P196 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P197 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P198 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P199 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P200 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P201 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P202 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P203 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P204 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P205 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P206 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P207 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P208 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P209 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P210 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P211 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P212 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P213 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P214 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P215 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P216 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P217 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P218 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P219 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P220 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P221 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P222 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P223 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P224 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P225 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P226 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P227 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P228 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P229 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P230 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P231 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P232 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P233 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P234 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P235 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P236 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P237 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P238 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P239 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P240 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P241 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P242 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P243 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P244 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P245 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P246 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P247 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P248 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P249 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P250 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P251 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P252 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P253 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P254 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P255 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P256 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P257 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P258 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P259 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P260 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P261 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P262 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P263 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P264 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P265 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P266 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P267 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P268 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P269 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P270 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P271 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P272 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P273 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P274 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P275 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P276 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P277 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P278 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P279 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P280 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P281 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P282 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P283 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P284 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P285 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P286 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P287 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P288 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P289 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P290 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P291 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P292 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P293 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P294 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P295 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P296 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P297 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P298 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P299 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P300 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P301 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P302 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P303 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P304 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P305 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P306 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P307 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P308 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P309 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P310 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P311 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P312 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P313 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P314 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P315 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P316 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P317 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P318 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P319 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P320 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P321 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P322 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P323 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P324 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P325 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P326 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P327 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P328 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P329 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P330 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P331 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P332 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P333 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P334 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P335 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P336 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P337 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P338 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P339 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P340 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P341 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P342 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P343 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P344 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P345 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P346 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P347 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P348 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P349 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P350 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P351 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P352 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P353 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P354 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P355 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P356 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P357 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P358 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P359 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P360 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P361 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P362 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P363 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P364 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P365 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P366 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P367 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P368 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P369 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P370 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P371 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P372 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P373 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P374 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P375 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P376 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P377 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P378 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P379 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P380 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P381 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P382 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P383 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P384 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P385 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P386 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P387 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P388 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P389 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P390 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P391 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P392 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P393 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P394 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P395 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P396 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P397 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P398 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |
| P399 | 0.00% [0.00%, 84.19%] (0/2) | 0.00% [0.00%, 84.19%] (0/2) | small N: interval too wide to rank |

Differential: undefined (a group has no errors or no data); compare the intervals instead.

### skinTone

| Group | BPCER [95% CI] | BPNRR [95% CI] | Note |
|---|---|---|---|
| MST1-2 | 0.00% [0.00%, 2.28%] (0/160) | 0.00% [0.00%, 2.28%] (0/160) |  |
| MST3-4 | 0.00% [0.00%, 2.28%] (0/160) | 0.00% [0.00%, 2.28%] (0/160) |  |
| MST5-6 | 0.00% [0.00%, 2.28%] (0/160) | 0.00% [0.00%, 2.28%] (0/160) |  |
| MST7-8 | 0.00% [0.00%, 2.28%] (0/160) | 0.00% [0.00%, 2.28%] (0/160) |  |
| MST9-10 | 0.00% [0.00%, 2.28%] (0/160) | 0.00% [0.00%, 2.28%] (0/160) |  |

Differential: undefined (a group has no errors or no data); compare the intervals instead.

## Per-cue ablation (APCER at the passive decision)

| Cue removed | mask-paper | print-matte-a4 | screen-phone-photo | screen-phone-video | BPCER |
|---|---|---|---|---|---|
| (none) | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% |
| flash | 15.5% | 0.0% | 0.0% | 0.0% | 0.0% |
| flatness | 0.0% | 0.5% | 0.0% | 0.0% | 0.0% |
| moire | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% |
| passive | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% |
