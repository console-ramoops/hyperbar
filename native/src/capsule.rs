//! HyperOS phone capsule sizing, recovered from the shipped plugin.
//!
//! Authority: MIUISystemUIPlugin.apk SHA-256
//! b76d60fee59b83b9a1edaddb694f30b274a6491281dd87fa82b995c85df56632,
//! `DynamicIslandContentViewPhoneHelper.java:59-165` in the local JADX output.
//! All dimensions are already in pixels. Runtime host measurements and resource
//! conversion remain explicit inputs; no fallback widths are invented here.

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct CapsuleInput {
    pub left_content_width: i32,
    pub right_content_width: i32,
    pub cutout_width: i32,
    pub screen_width: i32,
    pub screen_height: i32,
    pub max_width: f32,
    pub clock_width: f32,
    pub battery_width: f32,
    pub small_width: i32,
    pub gap: i32,
    pub min_big_width: i32,
    pub margin_width: i32,
    pub rtl: bool,
    pub fold_screen_layout_large: bool,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct CapsuleLayout {
    pub big_width: i32,
    pub left_width: i32,
    pub right_width: i32,
    pub big_x: i32,
    pub margin_width: i32,
    pub big_width_with_small: i32,
    pub left_width_with_small: i32,
    pub right_width_with_small: i32,
    pub big_x_with_small: i32,
}

// Kotlin/Java integer arithmetic wraps on overflow and division truncates toward
// zero. Ordinary UI dimensions never overflow, but wrapping keeps the donor's
// semantics under adversarial JNI inputs as well.
fn add(a: i32, b: i32) -> i32 {
    a.wrapping_add(b)
}
fn sub(a: i32, b: i32) -> i32 {
    a.wrapping_sub(b)
}

fn java_min_f32(a: f32, b: f32) -> f32 {
    // Math.min propagates NaN and favors negative zero, unlike Rust f32::min.
    if a.is_nan() || b.is_nan() {
        f32::NAN
    } else if a == 0.0 && b == 0.0 && (a.is_sign_negative() || b.is_sign_negative()) {
        -0.0
    } else if a <= b {
        a
    } else {
        b
    }
}

pub fn max_width_with_small(input: &CapsuleInput) -> f32 {
    let min_screen = input.screen_width.min(input.screen_height);
    if input.fold_screen_layout_large {
        return min_screen as f32 * 0.42_f32;
    }
    if input.clock_width == -1.0 || input.battery_width == -1.0 {
        return input.max_width;
    }
    // The donor divides the integer screen dimension BEFORE converting to f32.
    let half_screen = (min_screen / 2) as f32;
    let clock_available = half_screen - input.clock_width;
    let battery_available = half_screen - input.battery_width;
    let half_small = (input.small_width / 2) as f32;
    let balanced = java_min_f32(clock_available, battery_available - half_small);
    2.0_f32 * balanced + half_small
}

pub fn calculate_phone_capsule(input: &CapsuleInput) -> CapsuleLayout {
    let mut max_width = input.max_width;
    if input.fold_screen_layout_large {
        max_width = input.screen_width.min(input.screen_height) as f32 * 0.42_f32;
    }

    let widest_side = input.left_content_width.max(input.right_content_width);
    let doubled_side = add(widest_side, widest_side);
    let desired_big = add(input.cutout_width, doubled_side);
    // Rust's float-to-int cast has the JVM conversion's saturation/NaN rules.
    let big_width = desired_big.min(max_width as i32).max(input.min_big_width);
    let big_x = sub(input.screen_width, big_width) / 2;
    let side_width = if big_width < desired_big {
        sub(widest_side, sub(desired_big, big_width) / 2)
    } else {
        add(sub(sub(sub(big_width, widest_side), widest_side), input.cutout_width) / 2,
            widest_side)
    };

    let mut left = input.left_content_width;
    let mut right = input.right_content_width;
    let half_small = input.small_width / 2;
    if sub(left, right) > half_small {
        right = sub(left, half_small);
    } else {
        left = add(right, half_small);
    }
    let desired_pair = add(add(add(add(input.gap, input.cutout_width), left), right),
        input.small_width);
    let mut pair_width = max_width_with_small(input) as i32;
    if desired_pair <= pair_width {
        pair_width = desired_pair;
    }
    let minimum_pair = add(add(input.gap, input.min_big_width), input.small_width);
    if pair_width < minimum_pair {
        pair_width = minimum_pair;
    }
    let (left_pair, right_pair) = if pair_width < desired_pair {
        let shrink_each = sub(desired_pair, pair_width) / 2;
        (sub(left, shrink_each), sub(right, shrink_each))
    } else {
        let grow_each = sub(pair_width, desired_pair) / 2;
        (add(left, grow_each), add(right, grow_each))
    };
    let big_width_with_small = add(add(input.cutout_width, left_pair), right_pair);
    let big_x_with_small = if input.rtl {
        sub(add(add(input.cutout_width / 2, input.screen_width / 2), left_pair),
            big_width_with_small)
    } else {
        sub(sub(input.screen_width / 2, input.cutout_width / 2), left_pair)
    };

    CapsuleLayout {
        big_width,
        left_width: side_width,
        right_width: side_width,
        big_x,
        margin_width: input.margin_width,
        big_width_with_small,
        left_width_with_small: left_pair,
        right_width_with_small: right_pair,
        big_x_with_small,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn input(left: i32, right: i32) -> CapsuleInput {
        CapsuleInput {
            left_content_width: left, right_content_width: right,
            cutout_width: 30, screen_width: 393, screen_height: 852,
            max_width: 250.0, clock_width: 64.0, battery_width: 54.0,
            small_width: 34, gap: 4, min_big_width: 108, margin_width: 30,
            rtl: false, fold_screen_layout_large: false,
        }
    }

    #[test]
    fn asymmetric_pair_keeps_main_capsule_around_cutout() {
        let result = calculate_phone_capsule(&input(60, 40));
        assert_eq!(result, CapsuleLayout { big_width:150, left_width:60,
            right_width:60, big_x:121, margin_width:30, big_width_with_small:133,
            left_width_with_small:60, right_width_with_small:43,
            big_x_with_small:121 });
    }

    #[test]
    fn minimum_capsule_grows_both_sides_with_integer_truncation() {
        let result = calculate_phone_capsule(&input(10, 10));
        assert_eq!(result, CapsuleLayout { big_width:108, left_width:39,
            right_width:39, big_x:142, margin_width:30, big_width_with_small:107,
            left_width_with_small:47, right_width_with_small:30,
            big_x_with_small:134 });
        // 107 rather than 108 for the main part of this pair is donor behavior:
        // pair growth divides the odd remainder by two before applying it twice.
    }

    #[test]
    fn pair_uses_clock_and_battery_constraints_separately() {
        let result = calculate_phone_capsule(&input(130, 40));
        assert_eq!(result.big_width,250);
        assert_eq!(result.left_width,110);
        assert_eq!(result.big_width_with_small,229);
        assert_eq!(result.left_width_with_small,108);
        assert_eq!(result.right_width_with_small,91);
        assert_eq!(result.big_x_with_small,73);
    }

    #[test]
    fn rtl_mirrors_the_asymmetry_without_recentering() {
        let mut source=input(60,40);
        source.rtl=true;
        assert_eq!(calculate_phone_capsule(&source).big_x_with_small,138);
    }

    #[test]
    fn compatibility_sentinel_uses_the_supplied_maximum() {
        let mut source=input(130,40);
        source.clock_width=-1.0;
        let result=calculate_phone_capsule(&source);
        assert_eq!(result.big_width_with_small,213);
        assert_eq!(result.left_width_with_small,100);
        assert_eq!(result.right_width_with_small,83);
    }

    #[test]
    fn fold_constraint_applies_to_single_and_pair() {
        let mut source=input(130,40);
        source.fold_screen_layout_large=true;
        let result=calculate_phone_capsule(&source);
        assert_eq!(result.big_width,165);
        assert_eq!(result.left_width,68);
        assert_eq!(result.big_width_with_small,127);
        assert_eq!(result.left_width_with_small,57);
        assert_eq!(result.right_width_with_small,40);
    }

    #[test]
    fn java_float_min_preserves_nan_and_negative_zero() {
        assert!(java_min_f32(f32::NAN,1.0).is_nan());
        assert!(java_min_f32(0.0,-0.0).is_sign_negative());
        assert!(java_min_f32(-0.0,0.0).is_sign_negative());
    }
}
