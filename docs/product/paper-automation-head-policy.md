# PAPER automation head policy

Current branch head changes with every commit. All CI/review/merge decisions must use the latest exact head. If implementation continues after a green run, that green run becomes historical evidence only and the affected gates must be evaluated again.
