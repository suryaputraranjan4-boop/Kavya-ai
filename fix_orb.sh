sed -i 's/VoiceState.THINKING -> 1.04f/VoiceState.UNDERSTANDING -> 1.02f\n        VoiceState.THINKING -> 1.04f\n        VoiceState.EXECUTING -> 1.06f/g' app/src/main/java/com/example/ui/components/KavyaVoiceOrb.kt

sed -i 's/VoiceState.THINKING -> stateScale \* idleBreath \* pressScale/VoiceState.UNDERSTANDING -> stateScale \* idleBreath \* pressScale\n        VoiceState.THINKING -> stateScale \* idleBreath \* pressScale\n        VoiceState.EXECUTING -> stateScale \* speakingPulse \* pressScale/g' app/src/main/java/com/example/ui/components/KavyaVoiceOrb.kt

sed -i 's/VoiceState.THINKING -> AccentCyan.copy(alpha = 0.35f)/VoiceState.UNDERSTANDING -> AccentCyan.copy(alpha = 0.35f)\n        VoiceState.THINKING -> AccentCyan.copy(alpha = 0.35f)\n        VoiceState.EXECUTING -> AccentCyan.copy(alpha = 0.45f)/g' app/src/main/java/com/example/ui/components/KavyaVoiceOrb.kt

sed -i 's/VoiceState.THINKING -> listOf(AccentCyan, AccentPurple)/VoiceState.UNDERSTANDING -> listOf(AccentCyan, AccentPurple)\n        VoiceState.THINKING -> listOf(AccentCyan, AccentPurple)\n        VoiceState.EXECUTING -> listOf(AccentCyan, AccentPurpleLight)/g' app/src/main/java/com/example/ui/components/KavyaVoiceOrb.kt
