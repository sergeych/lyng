This is a branch with a kotlin compiler bug problem

The problem is: the code built for wasmJS target does not load (tests does not load),
the runtime error shows incorrect wasm binary content; $80 AI attempt to investigate
it results that most probable source of the bug is bad compilation of the _anonymous suspending lambda expressions_.

Sorry to say it is one of most used constructions in the Lyng compiler.

So we have to suspend wasmJS development.