For substantive work only, append one exact machine-readable marker to the final response:

`<!-- denden:event=completed -->`

Use one standardized event: `completed`, `failed`, `partial`, `blocked`, `needs-reply`, or `manual`. When elapsed time was measured reliably, use `<!-- denden:event=completed;durationSeconds=123;durationReliable=true -->`. Do not guess duration. Do not choose ring; the DenDen CLI applies user policy.
