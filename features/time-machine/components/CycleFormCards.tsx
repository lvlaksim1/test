import { Pressable, Text, View, type TextInputProps } from "react-native";

import { Field } from "@/features/time-machine/components/Field";
import type { NumericField } from "@/features/time-machine/form-utils";
import { styles } from "@/features/time-machine/styles";
import { useColors } from "@/hooks/use-colors";
import type { CycleForm } from "@/lib/cycle-utils";

type FocusHandler = NonNullable<TextInputProps["onFocus"]>;
type FieldChangeFactory = (field: "date" | "time") => (value: string) => void;
type NumericChangeFactory = (field: NumericField, allowNegative?: boolean) => (value: string) => void;

export function StartCard({ form, running, isBusy, onNow, onFieldChange, onFocus }: {
  form: CycleForm;
  running: boolean;
  isBusy: boolean;
  onNow: () => void;
  onFieldChange: FieldChangeFactory;
  onFocus: FocusHandler;
}) {
  const colors = useColors();
  return (
    <View style={[styles.card, { backgroundColor: colors.surface, borderColor: colors.border }]}>
      <View style={styles.cardHeading}>
        <Text style={[styles.cardTitle, { color: colors.text }]}>Старт</Text>
        <Pressable disabled={running || isBusy} onPress={onNow} style={({ pressed }) => [styles.nowButton, { borderColor: colors.primary }, (pressed || running || isBusy) && styles.pressed]}>
          <Text style={[styles.nowButtonText, { color: colors.primary }]}>Сейчас</Text>
        </Pressable>
      </View>
      <View style={styles.row}>
        <View style={styles.rowPrimary}>
          <Field label="Дата" value={form.date} onChangeText={onFieldChange("date")} onFocus={onFocus} placeholder="ДД.ММ.ГГГГ" editable={!running} />
        </View>
        <View style={styles.rowSecondary}>
          <Field label="Время" value={form.time} onChangeText={onFieldChange("time")} onFocus={onFocus} placeholder="ЧЧ:ММ" editable={!running} />
        </View>
      </View>
    </View>
  );
}

export function StepCard({ form, running, onNumericChange, onFocus }: {
  form: CycleForm;
  running: boolean;
  onNumericChange: NumericChangeFactory;
  onFocus: FocusHandler;
}) {
  const colors = useColors();
  return (
    <View style={[styles.card, { backgroundColor: colors.surface, borderColor: colors.border }]}>
      <Text style={[styles.cardTitle, { color: colors.text }]}>Шаг изменения</Text>
      <View style={styles.tripleRow}>
        <Field label="Дней" value={form.stepDays} onChangeText={onNumericChange("stepDays", true)} onFocus={onFocus} keyboardType="numeric" editable={!running} />
        <Field label="Часов" value={form.stepHours} onChangeText={onNumericChange("stepHours", true)} onFocus={onFocus} keyboardType="numeric" editable={!running} />
        <Field label="Минут" value={form.stepMinutes} onChangeText={onNumericChange("stepMinutes", true)} onFocus={onFocus} keyboardType="numeric" editable={!running} />
      </View>
    </View>
  );
}

export function RepetitionCard({ form, running, onNumericChange, onFocus }: {
  form: CycleForm;
  running: boolean;
  onNumericChange: NumericChangeFactory;
  onFocus: FocusHandler;
}) {
  const colors = useColors();
  return (
    <View style={[styles.card, { backgroundColor: colors.surface, borderColor: colors.border }]}>
      <Text style={[styles.cardTitle, { color: colors.text }]}>Повторение</Text>
      <View style={styles.repeatGroup}>
        <Text style={[styles.repeatGroupTitle, { color: colors.muted }]}>Вложенный цикл</Text>
        <View style={styles.row}>
          <View style={styles.rowPrimary}><Field label="Пауза, сек." value={form.pauseSeconds} onChangeText={onNumericChange("pauseSeconds")} onFocus={onFocus} keyboardType="number-pad" editable={!running} /></View>
          <View style={styles.rowSecondary}><Field label="Повторов" value={form.repeatsPerSeries} onChangeText={onNumericChange("repeatsPerSeries")} onFocus={onFocus} keyboardType="number-pad" editable={!running} /></View>
        </View>
      </View>
      <View style={[styles.repeatDivider, { backgroundColor: colors.border }]} />
      <View style={styles.repeatGroup}>
        <Text style={[styles.repeatGroupTitle, { color: colors.muted }]}>Главный цикл</Text>
        <View style={styles.row}>
          <View style={styles.rowPrimary}><Field label="Особая пауза, сек." value={form.seriesPauseSeconds} onChangeText={onNumericChange("seriesPauseSeconds")} onFocus={onFocus} keyboardType="number-pad" editable={!running} /></View>
          <View style={styles.rowSecondary}><Field label="Циклов" value={form.totalSeries} onChangeText={onNumericChange("totalSeries")} onFocus={onFocus} keyboardType="number-pad" editable={!running} /></View>
        </View>
      </View>
    </View>
  );
}
