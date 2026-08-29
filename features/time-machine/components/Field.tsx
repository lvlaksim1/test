import { Text, TextInput, View, type TextInputProps } from "react-native";

import { useColors } from "@/hooks/use-colors";
import { styles } from "@/features/time-machine/styles";

export type FieldProps = {
  label: string;
  value: string;
  onChangeText: (value: string) => void;
  onFocus?: TextInputProps["onFocus"];
  placeholder?: string;
  keyboardType?: TextInputProps["keyboardType"];
  editable?: boolean;
};

export function Field({ label, value, onChangeText, onFocus, placeholder = "", keyboardType = "default", editable = true }: FieldProps) {
  const colors = useColors();
  return (
    <View style={styles.fieldWrap}>
      <Text style={[styles.fieldLabel, { color: colors.muted }]}>{label}</Text>
      <TextInput
        value={value}
        onChangeText={onChangeText}
        onFocus={onFocus}
        placeholder={placeholder}
        placeholderTextColor={colors.muted}
        keyboardType={keyboardType}
        inputMode={keyboardType === "default" ? "text" : "numeric"}
        returnKeyType="done"
        editable={editable}
        style={[styles.input, { backgroundColor: colors.background, borderColor: colors.border, color: colors.text }]}
      />
    </View>
  );
}
