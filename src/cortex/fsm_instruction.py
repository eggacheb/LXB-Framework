"""
LXB-Cortex FSM Instruction Parser

This module provides parsing and validation for the Cortex DSL (Domain Specific Language)
used by the FSM engine to control Android automation. Instructions are simple text
commands like "TAP 500 800" or "SWIPE 540 1600 540 1400 650" that tell the engine
what actions to perform.

Supported Operations:
- SET_APP <package_name> - Select target app
- ROUTE <package_name> <target_page> - Plan route to target page
- TAP <x> <y> - Tap at coordinates
- SWIPE <x1> <y1> <x2> <y2> <duration_ms> - Swipe gesture
- INPUT "<text>" - Input text
- WAIT <ms> - Wait for milliseconds
- BACK - Press back key
- DONE - Mark task as complete
- FAIL <reason> - Fail with reason

Example:
    >>> from cortex.fsm_instruction import parse_instructions, validate_allowed
    >>>
    >>> text = '''TAP 500 800
    ... WAIT 1000'''
    >>> instructions = parse_instructions(text)
    >>> validate_allowed(instructions, {"TAP", "WAIT", "BACK"})
"""

import shlex
from dataclasses import dataclass
from typing import Iterable, List, Sequence, Set


class InstructionError(ValueError):
    """Exception raised when instruction parsing or validation fails.

    Attributes:
        message: Error description
    """
    pass


@dataclass(frozen=True)
class Instruction:
    """A single parsed instruction from the Cortex DSL.

    Attributes:
        op: Operation name (e.g., "TAP", "SWIPE", "BACK")
        args: List of argument strings for the operation
        raw: Original raw text line that produced this instruction
    """
    op: str
    args: List[str]
    raw: str


_ARG_COUNTS = {
    "SET_APP": (1, 1),
    "ROUTE": (2, 2),
    "TAP": (2, 2),
    "SWIPE": (5, 5),
    "INPUT": (1, 1),
    "WAIT": (1, 1),
    "BACK": (0, 0),
    "CHECK_POPUP": (0, 0),
    "ASSERT_PAGE": (1, 1),
    "RESTART_APP": (0, 0),
    "DONE": (0, 0),
    "FAIL": (1, 9999),
}
"""Argument count constraints for each operation.

Maps operation name to (min_args, max_args) tuple.
Operations with max_args=9999 accept unlimited arguments.
"""


def parse_instructions(text: str, max_commands: int = 3) -> List[Instruction]:
    """Parse instruction text into a list of Instruction objects.

    Parses multi-line text where each line is a shell-like command.
    Uses shlex.split for proper quoted argument handling.

    Args:
        text: Multi-line instruction text to parse
        max_commands: Maximum number of commands allowed (default: 3)

    Returns:
        List of parsed Instruction objects

    Raises:
        InstructionError: If text is empty, contains too many commands,
            has invalid quoting, or contains unknown/invalid operations

    Example:
        >>> parse_instructions("TAP 500 800\\nWAIT 1000")
        [Instruction(op='TAP', args=['500', '800'], raw='TAP 500 800'),
         Instruction(op='WAIT', args=['1000'], raw='WAIT 1000')]
    """
    lines = [line.strip() for line in (text or "").splitlines() if line.strip()]
    if not lines:
        raise InstructionError("empty instruction output")
    if len(lines) > max_commands:
        raise InstructionError(f"too many instructions: {len(lines)} > {max_commands}")

    out: List[Instruction] = []
    for line in lines:
        try:
            parts = shlex.split(line, posix=True)
        except ValueError as e:
            raise InstructionError(f"invalid instruction quoting: {line}") from e
        if not parts:
            continue
        op = parts[0].strip().upper()
        args = parts[1:]
        _validate_arity(op, args)
        out.append(Instruction(op=op, args=args, raw=line))

    if not out:
        raise InstructionError("no valid instructions parsed")
    return out


def validate_allowed(instructions: Sequence[Instruction], allowed_ops: Iterable[str]) -> None:
    """Validate that all instructions use allowed operations.

    Args:
        instructions: Sequence of Instruction objects to validate
        allowed_ops: Iterable of operation names that are permitted

    Raises:
        InstructionError: If any instruction uses an operation not in allowed_ops

    Example:
        >>> validate_allowed(
        ...     [Instruction(op='TAP', args=['500', '800'], raw='TAP 500 800')],
        ...     {'TAP', 'WAIT', 'BACK'}
        ... )
        >>> # No exception raised
        >>> validate_allowed(
        ...     [Instruction(op='SWIPE', args=['0','0','100','100','500'], raw='SWIPE ...')],
        ...     {'TAP'}
        ... )
        InstructionError: op not allowed in this state: SWIPE
    """
    allowed: Set[str] = {op.upper() for op in allowed_ops}
    for ins in instructions:
        if ins.op not in allowed:
            raise InstructionError(f"op not allowed in this state: {ins.op}")


def _validate_arity(op: str, args: List[str]) -> None:
    """Validate that an operation has the correct number of arguments.

    Args:
        op: Operation name to validate
        args: List of argument strings

    Raises:
        InstructionError: If op is unknown or has incorrect argument count
    """
    if op not in _ARG_COUNTS:
        raise InstructionError(f"unknown instruction op: {op}")
    min_args, max_args = _ARG_COUNTS[op]
    n = len(args)
    if n < min_args or n > max_args:
        expected = str(min_args) if min_args == max_args else f"{min_args}..{max_args}"
        raise InstructionError(f"{op} expects {expected} args, got {n}")

    if op not in _ARG_COUNTS:
        raise InstructionError(f"unknown instruction op: {op}")
    min_args, max_args = _ARG_COUNTS[op]
    n = len(args)
    if n < min_args or n > max_args:
        expected = str(min_args) if min_args == max_args else f"{min_args}..{max_args}"
        raise InstructionError(f"{op} expects {expected} args, got {n}")
