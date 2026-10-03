@echo off
rem Phase 11: every released arm on the 838 public held-out positions, then the
rem verifier over all of it. Resumes where it stopped; safe to start again.
rem   start "" /min training\tools\phase11_public_runs.cmd
rem Status: python training\tools\phase5_status.py training\results_private\phase8\grid_ab
cd /d "%~dp0.."
set PYTHONPATH=src
set LOG=results_private\phase11_public_runs.log
echo [%date% %time%] runs started >> %LOG%
python -m praxis_train.baselines run --data data/phase5 --run-dir results_private/phase8/grid_ab ^
  --arms lora-2b-r3,lora-2b-r0,lora-2b-r1,lora-2b-r2,lora-4b-r3,lora-4b-r0 ^
  --testset data/phase6_v1/test_ab_ablations.jsonl >> %LOG% 2>&1
echo [%date% %time%] runs finished, verifying >> %LOG%
"C:\Program Files\Git\bin\bash.exe" tools/javacli.sh EvalCli verify --testset data/phase6_v1/test_ab_ablations.jsonl ^
  --outputs results_private/phase8/grid_ab/outputs.jsonl --out results_private/phase8/grid_ab/verified.jsonl >> %LOG% 2>&1
echo [%date% %time%] ALL DONE >> %LOG%
