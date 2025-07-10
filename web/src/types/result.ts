export type Result<T> = {
	success: boolean;
	data: T;
	message: string;
}