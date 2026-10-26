import type { InputHTMLAttributes, Ref } from 'react';
import { Input } from './Field';

export interface TextFieldProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'id'> {
  label: string;
  hint?: string;
  error?: string | undefined;
  ref?: Ref<HTMLInputElement>;
}

/** Labelled text input for the signed-out flows; shares its look and wiring with `Input`. */
export function TextField(props: TextFieldProps) {
  return <Input {...props} />;
}
